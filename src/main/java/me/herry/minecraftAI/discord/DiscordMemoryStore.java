package me.herry.minecraftAI.discord;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import static me.herry.minecraftAI.discord.DiscordMemory.*;

/** Standalone Discord data worker; constructing and awaiting futures must happen off the server tick. */
public final class DiscordMemoryStore implements AutoCloseable {
    public record BackupPolicy(boolean enabled, long intervalMillis, int periodic, int daily, long maxBytes) {
        public BackupPolicy {
            if (intervalMillis < 1000 || periodic < 1 || periodic > 1000 || daily < 1 || daily > 365
                    || maxBytes < MemoryCodec.MAX_BYTES) throw new IllegalArgumentException("backup policy");
        }
        public static BackupPolicy defaults() { return new BackupPolicy(true, 600_000, 24, 7, 128L * 1024 * 1024); }
    }
    public record Status(long revision, boolean recovered, long recoveredAt, boolean closed) {}
    private final Path data, backups, current, journal;
    private final LongSupplier clock;
    private final BackupPolicy policy;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService timer;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Snapshot state;
    private volatile Throwable lastFailure;
    private boolean recovered;
    private long recoveredAt, backedRevision = -1;

    public DiscordMemoryStore(Path discordDirectory, BackupPolicy policy, LongSupplier clock) throws IOException {
        this.clock = java.util.Objects.requireNonNull(clock);
        this.policy = java.util.Objects.requireNonNull(policy);
        data = discordDirectory.toAbsolutePath().normalize().resolve("data");
        backups = discordDirectory.toAbsolutePath().normalize().resolve("backups");
        current = data.resolve("current.mem"); journal = data.resolve("deletions.mem");
        Files.createDirectories(data);
        lockChannel = FileChannel.open(data.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (RuntimeException | IOException e) { lockChannel.close(); throw new IOException("Discord memory already open", e); }
        if (acquired == null) { lockChannel.close(); throw new IOException("Discord memory already open"); }
        lock = acquired;
        try { state = load(); }
        catch (IOException | RuntimeException e) { lock.release(); lockChannel.close(); throw e; }
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
                r -> daemon(r, "MinecraftAI-discord-memory"), new ThreadPoolExecutor.AbortPolicy());
        timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "MinecraftAI-discord-backup"));
        if (policy.enabled) timer.scheduleWithFixedDelay(() -> backup(), policy.intervalMillis, policy.intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Store only a caller-confirmed minimal fact. LLM output must not directly call this method. */
    public CompletableFuture<Snapshot> remember(Key key, String value, Evidence evidence, String source, long expiresAt) {
        return submit(() -> {
            long revision = Math.addExact(state.revision(), 1);
            Fact fact = new Fact(key, value, evidence, source, clock.getAsLong(), expiresAt, revision);
            Map<Key, Fact> facts = new HashMap<>(state.facts());
            Map<Key, Long> erased = new HashMap<>(state.erasedKeys());
            Fact previous = facts.put(key, fact);
            if (previous != null) erased.merge(key, previous.revision(), Math::max);
            Snapshot next = snapshot(revision, facts, state.deleted(), erased);
            commit(next);
            return state;
        });
    }

    public CompletableFuture<Snapshot> forget(Subject person) {
        return submit(() -> {
            Map<Subject, Long> deleted = new HashMap<>(state.deleted());
            long revision = Math.addExact(state.revision(), 1);
            deleted.put(person, revision);
            Map<Key, Fact> facts = new HashMap<>(state.facts());
            facts.keySet().removeIf(key -> key.involves(person));
            commit(snapshot(revision, facts, deleted, state.erasedKeys()));
            return state;
        });
    }

    public CompletableFuture<Snapshot> forget(Key key) {
        return submit(() -> {
            long revision = Math.addExact(state.revision(), 1);
            Map<Key, Long> erased = new HashMap<>(state.erasedKeys()); erased.put(key, revision);
            Map<Key, Fact> facts = new HashMap<>(state.facts()); facts.remove(key);
            commit(snapshot(revision, facts, state.deleted(), erased));
            return state;
        });
    }

    /** Prompt data for one current participant; other users' personal facts never enter this result. */
    public CompletableFuture<List<Fact>> visible(Subject person, Set<String> participants) {
        Set<String> present = Set.copyOf(participants);
        return submit(() -> state.facts().values().stream()
                .filter(f -> f.key().subject().equals(person) && present.contains(person.userId()))
                .filter(f -> f.key().otherUserId().isEmpty() || present.contains(f.key().otherUserId()))
                .filter(f -> !f.expired(clock.getAsLong()) && !state.erased(f)).toList());
    }

    public CompletableFuture<Path> backup() {
        return submit(() -> {
            if (state.revision() == backedRevision) return null;
            long now = clock.getAsLong();
            Snapshot saved = snapshot(state.revision(), state.facts(), state.deleted(), state.erasedKeys());
            byte[] encoded = MemoryCodec.encode(saved);
            List<Path> existing = MemoryFiles.backups(backups);
            long total = 0;
            for (Path path : existing) total += Files.size(path);
            boolean newDay = existing.stream().noneMatch(p -> p.getFileName().toString().startsWith("daily-" + now / 86_400_000 + "-"));
            if (total + (long) encoded.length * (newDay ? 2 : 1) > policy.maxBytes) throw new IOException("backup capacity reached");
            Path path = backups.resolve("periodic-" + now + "-" + state.revision() + ".mem");
            MemoryFiles.write(path, saved);
            if (newDay) MemoryFiles.write(backups.resolve("daily-" + now / 86_400_000 + "-" + state.revision() + ".mem"), saved);
            prune("periodic-", policy.periodic); prune("daily-", policy.daily);
            backedRevision = state.revision();
            return path;
        });
    }

    /** Caller must pause Discord processing, invalidate turns, and clear its context before restore. */
    public CompletableFuture<Snapshot> restore(Path backup) {
        Path path = backup.toAbsolutePath().normalize();
        return submit(() -> {
            if (!path.getParent().equals(backups) || !MemoryFiles.backups(backups).contains(path)) throw new IOException("unknown backup");
            Snapshot candidate = MemoryFiles.read(path);
            Snapshot next = merge(candidate, state, Math.addExact(Math.max(candidate.revision(), state.revision()), 1));
            commit(next);
            return state;
        });
    }

    public CompletableFuture<Snapshot> snapshot() { return submit(() -> state); }
    public Status status() { return new Status(state.revision(), recovered, recoveredAt, closed.get()); }
    /** Diagnostics have no transcript, names, token, or file contents. */
    public boolean hasFailure() { return lastFailure != null; }

    private Snapshot load() throws IOException {
        if (!Files.exists(journal) && (Files.exists(current) || !MemoryFiles.backups(backups).isEmpty()))
            throw new IOException("missing Discord memory deletion journal");
        Snapshot deletion = Files.exists(journal) ? MemoryFiles.read(journal) : Snapshot.empty(clock.getAsLong());
        Snapshot loaded;
        if (!Files.exists(current)) {
            loaded = recoveryPoint();
            if (loaded == null) {
                if (!MemoryFiles.backups(backups).isEmpty()) throw new IOException("no valid Discord memory recovery point");
                loaded = Snapshot.empty(clock.getAsLong());
            } else { recovered = true; recoveredAt = loaded.createdAt(); }
        }
        else {
            try { loaded = MemoryFiles.read(current); }
            catch (IOException corrupt) {
                loaded = recoveryPoint();
                if (loaded == null) throw new IOException("no valid Discord memory recovery point", corrupt);
                Files.copy(current, data.resolve("corrupt-" + UUIDString() + ".mem"), StandardCopyOption.COPY_ATTRIBUTES);
                recovered = true; recoveredAt = loaded.createdAt();
            }
        }
        Snapshot merged = merge(loaded, deletion, Math.max(loaded.revision(), deletion.revision()));
        MemoryFiles.write(journal, snapshot(merged.revision(), Map.of(), merged.deleted(), merged.erasedKeys()));
        MemoryFiles.write(current, merged);
        return merged;
    }

    private Snapshot merge(Snapshot candidate, Snapshot deletion, long revision) {
        long now = clock.getAsLong();
        if (candidate.createdAt() > now || candidate.facts().values().stream().anyMatch(f -> f.recordedAt() > now))
            throw new IllegalArgumentException("future memory timestamp");
        Map<Subject, Long> deleted = new HashMap<>(candidate.deleted());
        deletion.deleted().forEach((key, floor) -> deleted.merge(key, floor, Math::max));
        Map<Key, Long> erased = new HashMap<>(candidate.erasedKeys());
        deletion.erasedKeys().forEach((key, floor) -> erased.merge(key, floor, Math::max));
        Snapshot filter = snapshot(revision, Map.of(), deleted, erased);
        Map<Key, Fact> facts = new HashMap<>(candidate.facts());
        facts.values().removeIf(f -> filter.erased(f) || f.expired(clock.getAsLong()));
        return snapshot(revision, facts, deleted, erased);
    }

    private Snapshot recoveryPoint() throws IOException {
        for (Path backup : MemoryFiles.backups(backups)) {
            try {
                Snapshot candidate = MemoryFiles.read(backup);
                long now = clock.getAsLong();
                if (candidate.createdAt() <= now && candidate.facts().values().stream().noneMatch(f -> f.recordedAt() > now)) return candidate;
            } catch (IOException ignored) { }
        }
        return null;
    }

    private void commit(Snapshot next) throws IOException {
        // Journal first: a failed data write or corrupt current file cannot revive erased facts.
        Snapshot deletion = snapshot(next.revision(), Map.of(), next.deleted(), next.erasedKeys());
        MemoryFiles.write(journal, deletion);
        state = merge(state, deletion, next.revision());
        MemoryFiles.write(current, next);
        state = next;
    }

    private Snapshot snapshot(long revision, Map<Key, Fact> facts, Map<Subject, Long> deleted, Map<Key, Long> erased) {
        return new Snapshot(revision, clock.getAsLong(), facts, deleted, erased);
    }
    private void prune(String prefix, int retain) throws IOException {
        List<Path> candidates = MemoryFiles.backups(backups).stream().filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
        for (Path path : candidates.stream().skip(retain).toList()) Files.delete(path);
    }
    private <T> CompletableFuture<T> submit(IOOperation<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("memory closed"));
        try {
            worker.execute(() -> {
                try { result.complete(operation.run()); }
                catch (Exception e) { lastFailure = e; result.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) { lastFailure = e; result.completeExceptionally(e); }
        return result;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        timer.shutdownNow();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS)) {
                lastFailure = new IOException("Discord memory shutdown timed out");
                // Keep the file lease until queued writes finish; a new runtime cannot race this writer.
                releaseAfterTermination();
            } else release();
        } catch (InterruptedException e) { releaseAfterTermination(); Thread.currentThread().interrupt(); lastFailure = e; }
    }
    private void releaseAfterTermination() {
        Thread cleanup = daemon(() -> {
            try { while (!worker.awaitTermination(1, TimeUnit.SECONDS)) { } release(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "MinecraftAI-discord-memory-close");
        cleanup.start();
    }
    private void release() {
        try { lock.release(); lockChannel.close(); } catch (IOException e) { lastFailure = e; }
    }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    private static String UUIDString() { return java.util.UUID.randomUUID().toString(); }
    @FunctionalInterface private interface IOOperation<T> { T run() throws Exception; }
}
