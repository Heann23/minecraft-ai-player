package me.herry.minecraftAI.persist;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Durable run intent only. Never stores world position, inventory or model weights. */
public final class ExecutionIntentStore implements AutoCloseable {
    private static final String SCHEMA = "minecraft-ai-execution-intent/v1";
    private final Path directory;
    private final Consumer<String> diagnostic;
    private final Gson gson = new Gson();
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<String, Long> latest = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Object publication = new Object();
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "minecraft-ai-execution-intent"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    record Pending(String key, long sequence, String text, UUID executionId, boolean running, CompletableFuture<Void> completion) {}

    public ExecutionIntentStore(Path directory, Consumer<String> diagnostic) {
        this.directory = directory.toAbsolutePath().normalize();
        this.diagnostic = diagnostic;
    }

    private static String key(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("invalid AI intent name");
        return name.toLowerCase(Locale.ROOT);
    }

    public CompletableFuture<Void> save(String name, UUID executionId, boolean running) {
        Pending request = prepare(name, executionId, running);
        try { writer.execute(() -> write(request)); }
        catch (RejectedExecutionException failure) {
            request.completion().completeExceptionally(failure);
            diagnostic.accept("AI execution intent pending; writer unavailable: " + request.key());
        }
        return request.completion();
    }

    Pending prepare(String name, UUID executionId, boolean running) {
        if (closed) throw new IllegalStateException("intent store closed");
        String key = key(name);
        JsonObject payload = new JsonObject();
        payload.addProperty("schema", SCHEMA); payload.addProperty("name", key);
        payload.addProperty("executionId", Objects.requireNonNull(executionId).toString()); payload.addProperty("running", running);
        JsonObject envelope = new JsonObject(); envelope.add("payload", payload);
        envelope.addProperty("sha256", hash(gson.toJson(payload)));
        long number = sequence.incrementAndGet();
        var request = new Pending(key, number, gson.toJson(envelope), executionId, running, new CompletableFuture<>());
        latest.merge(key, number, Math::max);
        pending.compute(key, (ignored, old) -> old == null || old.sequence() < number ? request : old);
        return request;
    }

    void write(Pending request) {
        Path temporary = directory.resolve(request.key() + "." + UUID.randomUUID() + ".tmp");
        try {
            if (latest.getOrDefault(request.key(), -1L) != request.sequence()) {
                request.completion().complete(null); return;
            }
            Files.createDirectories(directory);
            try (var stream = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer data = java.nio.charset.StandardCharsets.UTF_8.encode(request.text());
                while (data.hasRemaining()) stream.write(data);
                stream.force(true);
            }
            synchronized (publication) {
                if (latest.getOrDefault(request.key(), -1L) == request.sequence()) {
                    Files.move(temporary, directory.resolve(request.key() + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    diagnostic.accept("AI execution intent saved: " + request.key() + " running=" + request.running()
                            + " execution=" + request.executionId());
                }
            }
            pending.remove(request.key(), request);
            request.completion().complete(null);
        } catch (IOException | RuntimeException failure) {
            request.completion().completeExceptionally(failure);
            diagnostic.accept("AI execution intent save failed; previous record retained: " + request.key());
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    /** Missing/other incarnation: use the player snapshot. Corrupt record: fail closed and retain the original. */
    public Optional<Boolean> read(String name, UUID executionId) {
        String key = key(name);
        Path path = directory.resolve(key + ".json");
        try {
            if (Files.isSymbolicLink(path)) throw new IOException("unsafe intent reference");
            if (!Files.exists(path)) return Optional.empty();
            if (!Files.isRegularFile(path) || Files.size(path) > 4096) throw new IOException("invalid intent file");
            JsonObject envelope = gson.fromJson(Files.readString(path), JsonObject.class);
            JsonObject payload = envelope.getAsJsonObject("payload");
            if (payload == null || !hash(gson.toJson(payload)).equals(envelope.get("sha256").getAsString())
                    || !SCHEMA.equals(payload.get("schema").getAsString()) || !key.equals(payload.get("name").getAsString())
                    || !payload.getAsJsonPrimitive("running").isBoolean()) throw new IOException("intent integrity mismatch");
            UUID stored = UUID.fromString(payload.get("executionId").getAsString());
            return stored.equals(executionId) ? Optional.of(payload.get("running").getAsBoolean()) : Optional.empty();
        } catch (IOException | RuntimeException failure) {
            diagnostic.accept("AI execution intent unreadable; restoring stopped: " + key);
            return Optional.of(false);
        }
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public void close() {
        closed = true; writer.shutdown();
        try { writer.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        // Only the latest immutable value is flushed; late older writes cannot replace it.
        for (Pending request : List.copyOf(pending.values())) write(request);
    }
}
