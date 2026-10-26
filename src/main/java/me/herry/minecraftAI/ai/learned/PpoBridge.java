package me.herry.minecraftAI.ai.learned;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Local development-only file transport. World access never leaves the server thread. No network or LLM commands. */
public final class PpoBridge implements AutoCloseable {
    public static final String SCHEMA = "minecraft-paper-ppo/v2";
    public static final String CONTROL_SCHEMA = "minecraft-brain-control/v1";
    public record Status(long modelVersion, long environmentSteps, Metadata metadata) {}
    public record Control(String schema, String id, String operation, String brainId, boolean enabled) {}
    public record ControlResult(String schema, String id, boolean ok, String error, String weightsSha256, Status status) {}
    private final java.util.concurrent.atomic.AtomicBoolean transitioning = new java.util.concurrent.atomic.AtomicBoolean();
    public boolean beginTransition() { return transitioning.compareAndSet(false, true); }
    public boolean isTransitioning() { return transitioning.get(); }
    public void endTransition() { transitioning.set(false); }
    public record Transition(String session, long sequence, PolicyFrame frame, int index,
                             double logProbability, double value, long modelVersion, double reward,
                             int elapsedTicks, boolean terminated, boolean truncated, long appliedTick, String nextWorld, double[] nextState) {
        public Transition {
            UUID.fromString(session); UUID.fromString(nextWorld);
            if (appliedTick < frame.serverTick() || appliedTick > 0xffff_ffffL || nextState.length != PolicyFrame.STATE_SIZE)
                throw new IllegalArgumentException("invalid applied tick/next state");
            nextState = nextState.clone();
            for (double feature : nextState) if (!Double.isFinite(feature) || Math.abs(feature) > 16)
                throw new IllegalArgumentException("invalid actual next state");
            if (sequence < 0 || index < 0 || index >= frame.options().size() || modelVersion < 0
                    || elapsedTicks < 0 || elapsedTicks > 2400 || !Double.isFinite(logProbability) || logProbability > 0
                    || !Double.isFinite(value) || !Double.isFinite(reward) || Math.abs(reward) > 100)
                throw new IllegalArgumentException("invalid actual PPO transition");
        }
        @Override public double[] nextState() { return nextState.clone(); }
    }
    public record Query(String schema, String environment, String id, PolicyFrame frame, java.util.List<Transition> transitions) {
        public Query { transitions = java.util.List.copyOf(transitions); if (transitions.size() > 16) throw new IllegalArgumentException("transition batch too large"); }
    }
    public record Response(String schema, String id, int index, String candidate, double logProbability,
                           double value, long modelVersion, long environmentSteps, Metadata metadata) {
        public Response(String schema, String id, int index, String candidate, double logProbability,
                        double value, long modelVersion, long environmentSteps) {
            this(schema, id, index, candidate, logProbability, value, modelVersion, environmentSteps, null);
        }
    }
    public record Metadata(String brainId, String trainingStartState, long gradientUpdates,
                           boolean learningEnabled, String saveState, String checkpointSha256, Boolean initializationOnly) {
        public Metadata(String brainId, String trainingStartState, long gradientUpdates,
                        boolean learningEnabled, String saveState, String checkpointSha256) {
            this(brainId, trainingStartState, gradientUpdates, learningEnabled, saveState, checkpointSha256, null);
        }
        public Metadata {
            UUID.fromString(brainId);
            if (!java.util.Set.of("fresh", "pretrained", "resume").contains(trainingStartState) || gradientUpdates < 0
                    || !java.util.Set.of("saved", "updating", "not-saved").contains(saveState)
                    || checkpointSha256 != null && !checkpointSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid brain metadata");
        }
    }
    private final Path directory;
    private final ExecutorService io = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), r -> {
                Thread thread = new Thread(r, "minecraft-ai-ppo-io"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Gson gson = new Gson();

    public PpoBridge(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
    }

    public CompletableFuture<Response> request(PolicyFrame frame, java.util.List<Transition> transitions) {
        String id = UUID.randomUUID().toString();
        String payload = gson.toJson(new Query(SCHEMA, "minecraft-paper", id, frame, transitions));
        return CompletableFuture.supplyAsync(() -> exchange(id, payload, frame), io);
    }

    public CompletableFuture<ControlResult> setLearning(String brainId, boolean enabled) {
        UUID.fromString(brainId);
        String id = UUID.randomUUID().toString();
        String payload = gson.toJson(new Control(CONTROL_SCHEMA, id, "learning", brainId, enabled));
        return CompletableFuture.supplyAsync(() -> exchangeControl(id, payload, brainId, enabled), io);
    }

    private ControlResult exchangeControl(String id, String payload, String brainId, boolean enabled) {
        Path temporary = directory.resolve(id + ".control.tmp");
        Path request = directory.resolve(id + ".control.json");
        Path response = directory.resolve(id + ".control-response.json");
        try {
            Files.writeString(temporary, payload, StandardOpenOption.CREATE_NEW);
            Files.move(temporary, request, StandardCopyOption.ATOMIC_MOVE);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                if (Files.isRegularFile(response)) {
                    if (Files.size(response) > 16384) throw new IOException("oversized brain control response");
                    var result = gson.fromJson(Files.readString(response), ControlResult.class);
                    if (result == null || !CONTROL_SCHEMA.equals(result.schema()) || !id.equals(result.id()))
                        throw new IOException("invalid brain control response identity");
                    if (!result.ok()) throw new IOException("brain state change rejected");
                    var status = result.status();
                    if (status == null || status.metadata() == null || status.modelVersion() < 0 || status.environmentSteps() < 0
                            || !brainId.equals(status.metadata().brainId()) || status.metadata().learningEnabled() != enabled
                            || result.weightsSha256() == null || !result.weightsSha256().matches("[0-9a-f]{64}"))
                        throw new IOException("brain control acknowledgement mismatch");
                    return result;
                }
                Thread.sleep(10);
            }
            throw new IOException("brain control timeout; change not confirmed");
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CompletionException(failure);
        } finally {
            for (Path path : new Path[]{temporary, request, response}) try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }
    }

    private Response exchange(String id, String payload, PolicyFrame frame) {
        Path temporary = directory.resolve(id + ".request.tmp");
        Path request = directory.resolve(id + ".request.json");
        Path response = directory.resolve(id + ".response.json");
        try {
            Files.writeString(temporary, payload, StandardOpenOption.CREATE_NEW);
            Files.move(temporary, request, StandardCopyOption.ATOMIC_MOVE);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                if (Files.isRegularFile(response)) {
                    if (Files.size(response) > 16384) throw new IOException("oversized PPO response");
                    Response result = gson.fromJson(Files.readString(response), Response.class);
                    if (result == null || !SCHEMA.equals(result.schema()) || !id.equals(result.id())
                            || result.index() < 0 || result.index() >= frame.options().size()
                            || !frame.options().get(result.index()).id().equals(result.candidate())
                            || !Double.isFinite(result.logProbability()) || result.logProbability() > 0
                            || !Double.isFinite(result.value()) || result.modelVersion() < 0 || result.environmentSteps() < 0)
                        throw new IOException("invalid/stale PPO response");
                    return result;
                }
                Thread.sleep(10);
            }
            throw new IOException("PPO trainer unavailable/timeout");
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CompletionException(failure);
        } finally {
            for (Path path : new Path[]{temporary, request, response}) try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }
    }

    @Override public void close() {
        io.shutdown();
        try { if (!io.awaitTermination(2, TimeUnit.SECONDS)) io.shutdownNow(); }
        catch (InterruptedException interrupted) { io.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
