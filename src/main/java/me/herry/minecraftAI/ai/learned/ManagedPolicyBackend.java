package me.herry.minecraftAI.ai.learned;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** Owns one explicit local backend process. Contains no Bukkit, world or player state. */
public final class ManagedPolicyBackend implements AutoCloseable {
    private static final String SCHEMA = "minecraft-managed-brain-session/v2";
    private static final ConcurrentMap<Path, ManagedPolicyBackend> OWNERS = new ConcurrentHashMap<>();
    public record Settings(String python, Path script, Path catalog, Path runs, Path channelRoot,
                           String model, boolean allowTraining, int maxTransitions, int sessionSaveSeconds) {
        public Settings {
            if (python == null || python.isBlank() || script == null || catalog == null || runs == null || channelRoot == null
                    || model == null || !model.isEmpty() && !PpoBridge.validModelName(model)
                    || maxTransitions < 32 || sessionSaveSeconds < 1 || sessionSaveSeconds > 3600)
                throw new IllegalArgumentException("invalid managed brain settings");
            script = script.toAbsolutePath().normalize(); catalog = catalog.toAbsolutePath().normalize();
            runs = runs.toAbsolutePath().normalize(); channelRoot = channelRoot.toAbsolutePath().normalize();
        }
    }
    private final Settings settings;
    @FunctionalInterface interface Launcher { Process launch(List<String> command, Path log) throws IOException; }
    private final Launcher launcher;
    private final String run = "run-" + UUID.randomUUID();
    private final Path output;
    private final Path channel;
    private final Gson gson = new Gson();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "minecraft-ai-brain-process"); thread.setDaemon(true); return thread;
    });
    private volatile Process process;
    private volatile PpoBridge.Status readyStatus;
    private volatile boolean closed;
    private FileChannel owner;
    private FileLock lock;
    private CompletableFuture<String> startup;

    public ManagedPolicyBackend(Settings settings) {
        this(settings, (command, log) -> new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start());
    }

    ManagedPolicyBackend(Settings settings, Launcher launcher) {
        this.settings = settings;
        this.launcher = launcher;
        output = settings.runs().resolve(run);
        // Never let a new server consume UUID requests or terminal transitions left by an earlier server.
        channel = settings.channelRoot().resolve(run);
    }

    public Path channel() { return channel; }
    public PpoBridge.Status readyStatus() { return readyStatus; }

    public synchronized CompletableFuture<String> start() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("brain manager is closed"));
        if (startup != null) return startup;
        startup = CompletableFuture.supplyAsync(() -> {
            try { return launch(); }
            catch (IOException | InterruptedException | RuntimeException failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                stopChild(); releaseOwner();
                throw new CompletionException(failure);
            }
        }, worker);
        return startup;
    }

    public CompletableFuture<Integer> exitCode() {
        Process child = process;
        return child == null ? CompletableFuture.failedFuture(new IllegalStateException("backend not started"))
                : child.onExit().thenApply(Process::exitValue);
    }

    public boolean isClosed() { return closed; }

    private String launch() throws IOException, InterruptedException {
        if (!Files.isRegularFile(settings.script()) || Files.isSymbolicLink(settings.runs()))
            throw new IOException("managed backend script missing or runtime directory unsafe");
        Files.createDirectories(settings.runs());
        if (OWNERS.putIfAbsent(settings.runs(), this) != null)
            throw new IOException("another backend in this server owns the runtime directory");
        owner = FileChannel.open(settings.runs().resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = owner.tryLock();
        if (lock == null) throw new IOException("another backend owns the runtime directory");
        String expectedBrain = null;
        Path restored = null;
        Path pointer = settings.runs().resolve("current-session.json");
        if (Files.exists(pointer)) {
            JsonObject record = readObject(pointer);
            JsonObject payload = record.getAsJsonObject("payload");
            if (payload == null || !java.util.Set.of(SCHEMA, "minecraft-managed-brain-session/v1").contains(payload.get("schema").getAsString())
                    || !digest(gson.toJson(payload)).equals(record.get("sha256").getAsString()))
                throw new IOException("managed session pointer is corrupt; no automatic initialization");
            if ("minecraft-managed-brain-session/v1".equals(payload.get("schema").getAsString())) {
                expectedBrain = payload.get("brainId").getAsString(); UUID.fromString(expectedBrain);
            } else {
                UUID.fromString(payload.get("startupBrainId").getAsString());
                // Active identity is bound by the checksummed runtime session and verified optimizer checkpoint.
                // An explicit model load may change it after startup while this owned session reference stays fixed.
            }
            String relative = payload.get("session").getAsString();
            if (!relative.matches("run-[0-9a-f-]{36}/runtime-session\\.json")) throw new IOException("unsafe session reference");
            restored = settings.runs().resolve(relative).normalize();
            if (!restored.startsWith(settings.runs()) || Files.isSymbolicLink(restored.getParent()) || !Files.isRegularFile(restored))
                throw new IOException("saved session missing; original model is not replaced");
        }
        if (closed) throw new IOException("managed startup cancelled");
        Files.createDirectories(channel);
        var command = new java.util.ArrayList<>(List.of(settings.python(), "-u", settings.script().toString(),
                "--channel", channel.toString(), "--output", output.toString(), "--max-transitions", Integer.toString(settings.maxTransitions()),
                "--rollout-size", "32", "--stop-file", settings.runs().resolve(run + ".stop").toString(),
                "--session-save-seconds", Integer.toString(settings.sessionSaveSeconds()), "--model-catalog", settings.catalog().toString()));
        if (restored != null) command.addAll(List.of("--restore-session", restored.toString()));
        else {
            command.addAll(List.of("--catalog", settings.catalog().toString()));
            if (!settings.model().isEmpty()) command.addAll(List.of("--restore-model", settings.model()));
        }
        if (!settings.allowTraining()) command.add("--inference-only");
        process = launcher.launch(List.copyOf(command), settings.runs().resolve(run + ".log"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!closed && process.isAlive() && System.nanoTime() < deadline) {
            if (Files.isRegularFile(output.resolve("run.json")) && Files.isRegularFile(output.resolve("runtime-session.json"))) {
                JsonObject state = readObject(output.resolve("runtime-session.json")).getAsJsonObject("payload");
                String brain = state.get("brainId").getAsString(); UUID.fromString(brain);
                if (!"minecraft-brain-runtime-session/v1".equals(state.get("schema").getAsString())
                        || expectedBrain != null && !expectedBrain.equals(brain)
                        || !settings.allowTraining() && state.get("learningEnabled").getAsBoolean())
                    throw new IOException("managed brain restoration mismatch; no new model selected");
                var metadata = new PpoBridge.Metadata(brain, state.get("startState").getAsString(),
                        state.get("gradientUpdates").getAsLong(), state.get("learningEnabled").getAsBoolean(), "saved",
                        state.get("checkpointSha256").getAsString(), state.get("initializationOnly").getAsBoolean());
                long version = state.get("policyVersion").getAsLong(), steps = state.get("environmentSteps").getAsLong();
                if (version < 0 || steps < 0) throw new IOException("invalid restored policy counters");
                readyStatus = new PpoBridge.Status(version, steps, metadata);
                JsonObject payload = new JsonObject();
                payload.addProperty("schema", SCHEMA); payload.addProperty("startupBrainId", brain);
                payload.addProperty("session", run + "/runtime-session.json");
                JsonObject record = new JsonObject(); record.add("payload", payload); record.addProperty("sha256", digest(gson.toJson(payload)));
                replace(pointer, gson.toJson(record));
                return brain;
            }
            Thread.sleep(25);
        }
        throw new IOException("managed brain did not become ready; inspect its private log");
    }

    private JsonObject readObject(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > 65536)
            throw new IOException("invalid managed brain state file");
        try { return gson.fromJson(Files.readString(path), JsonObject.class); }
        catch (RuntimeException failure) { throw new IOException("unreadable managed brain state", failure); }
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void replace(Path path, String text) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (var stream = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = java.nio.charset.StandardCharsets.UTF_8.encode(text);
                while (bytes.hasRemaining()) stream.write(bytes);
                stream.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void stopChild() {
        Process child = process;
        if (child == null || !child.isAlive()) return;
        try {
            Files.writeString(settings.runs().resolve(run + ".stop"), "stop", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroy();
            if (child.isAlive() && !child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly();
        } catch (IOException failure) { child.destroy(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); child.destroy(); }
    }

    private synchronized void releaseOwner() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) {}
        try { if (owner != null) owner.close(); } catch (IOException ignored) {}
        lock = null; owner = null;
        OWNERS.remove(settings.runs(), this);
    }

    @Override public void close() {
        closed = true;
        CompletableFuture<String> started = startup;
        if (started != null) started.handle((ignored, failure) -> { stopChild(); releaseOwner(); return null; });
        else releaseOwner();
        stopChild();
        worker.shutdown();
    }
}
