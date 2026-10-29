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
    public record ModelCommand(String schema, String id, String operation, String brainId, String name) {}
    public record ModelSummary(String name, boolean valid, String brainId, long policyVersion,
                               long gradientUpdates, boolean learningEnabled, boolean initializationOnly) {}
    public record ModelResult(String schema, String id, boolean ok, String error, String operation, String name,
                              String weightsSha256, Status status, java.util.List<ModelSummary> models, boolean hasMore,
                              String previousBrainId) {}
    public static final class ModelRejectedException extends IOException {
        public ModelRejectedException() { super("model command rejected before commit"); }
    }
    private final Object identityLock = new Object();
    private long brainGeneration;
    private String expectedBrainId;
    private String controlEpoch;
    private final java.util.concurrent.atomic.AtomicBoolean transitioning = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean available = true;
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean value) { available = value; }
    public boolean beginTransition() { return transitioning.compareAndSet(false, true); }
    public boolean isTransitioning() { return transitioning.get(); }
    public void endTransition() { transitioning.set(false); }
    public record Transition(String session, long sequence, PolicyFrame frame, int index,
                             double logProbability, double value, long modelVersion, double reward,
                             int elapsedTicks, boolean terminated, boolean truncated, long appliedTick, String nextWorld, double[] nextState,
                             String rewardContract, java.util.List<String> objectiveEvents, RewardEvidence rewardEvidence,
                             PolicyTaskMemory.View nextTaskMemory) {
        public Transition(String session, long sequence, PolicyFrame frame, int index,
                          double logProbability, double value, long modelVersion, double reward,
                          int elapsedTicks, boolean terminated, boolean truncated, long appliedTick, String nextWorld, double[] nextState,
                          String rewardContract, java.util.List<String> objectiveEvents, RewardEvidence rewardEvidence) {
            this(session, sequence, frame, index, logProbability, value, modelVersion, reward, elapsedTicks,
                    terminated, truncated, appliedTick, nextWorld, nextState, rewardContract, objectiveEvents, rewardEvidence, null);
        }
        public Transition(String session, long sequence, PolicyFrame frame, int index,
                          double logProbability, double value, long modelVersion, double reward,
                          int elapsedTicks, boolean terminated, boolean truncated, long appliedTick, String nextWorld, double[] nextState,
                          String rewardContract, java.util.List<String> objectiveEvents) {
            this(session, sequence, frame, index, logProbability, value, modelVersion, reward, elapsedTicks,
                    terminated, truncated, appliedTick, nextWorld, nextState, rewardContract, objectiveEvents, null);
        }
        public Transition(String session, long sequence, PolicyFrame frame, int index,
                          double logProbability, double value, long modelVersion, double reward,
                          int elapsedTicks, boolean terminated, boolean truncated, long appliedTick, String nextWorld, double[] nextState) {
            this(session, sequence, frame, index, logProbability, value, modelVersion, reward, elapsedTicks,
                    terminated, truncated, appliedTick, nextWorld, nextState, "survival-progress/v1", java.util.List.of());
        }
        public Transition {
            if (rewardEvidence != null && (!"survival-progress/v2".equals(rewardContract)
                    || Math.abs(rewardEvidence.total(elapsedTicks, terminated) - reward) > 1e-9))
                throw new IllegalArgumentException("actual reward evidence mismatch");
            if (!java.util.Set.of("survival-progress/v1", "survival-progress/v2").contains(rewardContract))
                throw new IllegalArgumentException("unsupported actual reward contract");
            objectiveEvents = java.util.List.copyOf(objectiveEvents);
            if (objectiveEvents.size() > 32 || objectiveEvents.stream().distinct().count() != objectiveEvents.size()
                    || objectiveEvents.stream().anyMatch(key -> key.length() > 268 || !key.matches("advancement:[a-z0-9_.-]+:[a-z0-9_./-]+"))
                    || !objectiveEvents.isEmpty() && !rewardContract.equals("survival-progress/v2"))
                throw new IllegalArgumentException("invalid observed objective events");
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
    /** Admission-time observations, independent of the earlier policy request snapshot. No policy features. */
    public record RewardEvidence(String schema, double healthBefore, double healthAfter,
                                 int foodBefore, int foodAfter, double saturationBefore, double saturationAfter,
                                 int airBefore, int airAfter, int discoveries, double objectiveReward) {
        public RewardEvidence {
            if (!"actual-reward-evidence/v1".equals(schema) || discoveries < 0
                    || foodBefore < 0 || foodBefore > 20 || foodAfter < 0 || foodAfter > 20
                    || healthBefore < 0 || healthAfter < 0 || saturationBefore < 0 || saturationAfter < 0)
                throw new IllegalArgumentException("invalid reward observations");
            for (double scalar : new double[]{healthBefore, healthAfter, saturationBefore, saturationAfter, objectiveReward})
                if (!Double.isFinite(scalar)) throw new IllegalArgumentException("nonfinite reward evidence");
        }
        public double total(int elapsedTicks, boolean terminated) {
            return Math.clamp(elapsedTicks * 0.0001 + (healthAfter - healthBefore) * 0.05
                    + discoveries * 0.2 + objectiveReward - (terminated ? 10 : 0), -100, 100);
        }
    }
    public record Query(String schema, String environment, String id, PolicyFrame frame, java.util.List<Transition> transitions, String brainId, String controlEpoch) {
        public Query(String schema, String environment, String id, PolicyFrame frame, java.util.List<Transition> transitions) {
            this(schema, environment, id, frame, transitions, null, null);
        }
        public Query(String schema, String environment, String id, PolicyFrame frame, java.util.List<Transition> transitions, String brainId) {
            this(schema, environment, id, frame, transitions, brainId, null);
        }
        public Query {
            transitions = java.util.List.copyOf(transitions);
            if (transitions.size() > 16) throw new IllegalArgumentException("transition batch too large");
            if (brainId != null) UUID.fromString(brainId);
            if (controlEpoch != null) UUID.fromString(controlEpoch);
        }
    }
    public record Response(String schema, String id, int index, String candidate, double logProbability,
                           double value, long modelVersion, long environmentSteps, Metadata metadata) {
        public Response(String schema, String id, int index, String candidate, double logProbability,
                        double value, long modelVersion, long environmentSteps) {
            this(schema, id, index, candidate, logProbability, value, modelVersion, environmentSteps, null);
        }
    }
    public record Metadata(String brainId, String trainingStartState, long gradientUpdates,
                           boolean learningEnabled, String saveState, String checkpointSha256, Boolean initializationOnly,
                           Long collectedEnvironmentSteps, Long trainedEnvironmentSteps, Long pendingRolloutTransitions) {
        public Metadata(String brainId, String trainingStartState, long gradientUpdates,
                        boolean learningEnabled, String saveState, String checkpointSha256, Boolean initializationOnly) {
            this(brainId, trainingStartState, gradientUpdates, learningEnabled, saveState, checkpointSha256, initializationOnly, null, null, null);
        }
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
            if (collectedEnvironmentSteps != null && collectedEnvironmentSteps < 0
                    || trainedEnvironmentSteps != null && trainedEnvironmentSteps < 0
                    || pendingRolloutTransitions != null && pendingRolloutTransitions < 0
                    || collectedEnvironmentSteps != null && trainedEnvironmentSteps != null && pendingRolloutTransitions != null
                    && (trainedEnvironmentSteps > collectedEnvironmentSteps
                    || collectedEnvironmentSteps - trainedEnvironmentSteps != pendingRolloutTransitions))
                throw new IllegalArgumentException("invalid brain transition counters");
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
        synchronized (identityLock) {
            long generation = brainGeneration;
            String payload = gson.toJson(new Query(SCHEMA, "minecraft-paper", id, frame, transitions, expectedBrainId, controlEpoch));
            return CompletableFuture.supplyAsync(() -> exchange(id, payload, frame, generation), io);
        }
    }

    public CompletableFuture<ControlResult> setLearning(String brainId, boolean enabled) {
        UUID.fromString(brainId);
        String id = UUID.randomUUID().toString();
        String payload = gson.toJson(new Control(CONTROL_SCHEMA, id, "learning", brainId, enabled));
        return CompletableFuture.supplyAsync(() -> exchangeControl(id, payload, brainId, enabled), io);
    }

    public CompletableFuture<ModelResult> modelCommand(String brainId, String operation, String name) {
        UUID.fromString(brainId);
        if (!java.util.Set.of("list", "info", "save", "new", "load").contains(operation)
                || (operation.equals("list") ? !name.isEmpty() : !validModelName(name)))
            throw new IllegalArgumentException("invalid model command");
        String id = UUID.randomUUID().toString();
        var command = new ModelCommand(CONTROL_SCHEMA, id, "model-" + operation, brainId, name);
        String payload = gson.toJson(command);
        synchronized (identityLock) {
            long generation = brainGeneration;
            return CompletableFuture.supplyAsync(() -> exchangeModel(command, payload, generation), io);
        }
    }

    public static boolean validModelName(String name) {
        return name != null && name.matches("[a-z0-9][a-z0-9_-]{0,63}") && !name.equals("selected-model")
                && !name.startsWith("revision-") && !name.matches("con|prn|aux|nul|com[0-9]|lpt[0-9]");
    }

    private ModelResult exchangeModel(ModelCommand command, String payload, long generation) {
        Path temporary = directory.resolve(command.id() + ".control.tmp");
        Path request = directory.resolve(command.id() + ".control.json");
        Path response = directory.resolve(command.id() + ".control-response.json");
        try {
            Files.writeString(temporary, payload, StandardOpenOption.CREATE_NEW);
            Files.move(temporary, request, StandardCopyOption.ATOMIC_MOVE);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                if (Files.isRegularFile(response)) {
                    if (Files.size(response) > 16384) throw new IOException("oversized model response");
                    var result = gson.fromJson(Files.readString(response), ModelResult.class);
                    if (result == null || !CONTROL_SCHEMA.equals(result.schema()) || !command.id().equals(result.id()))
                        throw new IOException("invalid model response identity");
                    if (!result.ok()) throw new ModelRejectedException();
                    var status = result.status();
                    boolean loading = command.operation().equals("model-load");
                    if (!command.operation().equals(result.operation()) || !command.name().equals(result.name())
                            || status == null || status.metadata() == null || status.modelVersion() < 0 || status.environmentSteps() < 0
                            || (loading ? !command.brainId().equals(result.previousBrainId()) : !command.brainId().equals(status.metadata().brainId()))
                            || result.weightsSha256() == null || !result.weightsSha256().matches("[0-9a-f]{64}")
                            || result.models() == null || result.models().size() > 32
                            || !command.operation().equals("model-list") && result.models().size() != 1)
                        throw new IOException("model acknowledgement mismatch");
                    for (var model : result.models()) {
                        if (model == null || !validModelName(model.name())) throw new IOException("invalid model entry");
                        if (model.valid()) {
                            UUID.fromString(model.brainId());
                            if (model.policyVersion() < 0 || model.gradientUpdates() < 0)
                                throw new IOException("invalid model counters");
                        }
                    }
                    if (!command.operation().equals("model-list") && !command.name().equals(result.models().getFirst().name()))
                        throw new IOException("model name acknowledgement mismatch");
                    if (command.operation().equals("model-new")) {
                        var created = result.models().getFirst();
                        if (!created.valid() || !created.initializationOnly() || created.policyVersion() != 0 || created.gradientUpdates() != 0
                                || created.learningEnabled() || command.brainId().equals(created.brainId()))
                            throw new IOException("new model must be a distinct untrained, inactive brain");
                    }
                    if (command.operation().equals("model-save")) {
                        var saved = result.models().getFirst();
                        if (!saved.valid() || !command.name().equals(saved.name()) || !command.brainId().equals(saved.brainId())
                                || saved.policyVersion() != status.modelVersion()
                                || saved.gradientUpdates() != status.metadata().gradientUpdates()
                                || saved.learningEnabled() != status.metadata().learningEnabled())
                            throw new IOException("saved model differs from active brain");
                    }
                    if (loading) {
                        var loaded = result.models().getFirst();
                        if (!loaded.valid() || !loaded.brainId().equals(status.metadata().brainId())
                                || loaded.policyVersion() != status.modelVersion() || loaded.gradientUpdates() != status.metadata().gradientUpdates()
                                || loaded.learningEnabled() != status.metadata().learningEnabled())
                            throw new IOException("loaded model differs from runtime acknowledgement");
                        synchronized (identityLock) {
                            if (brainGeneration != generation) throw new IOException("stale model load acknowledgement");
                            expectedBrainId = loaded.brainId(); controlEpoch = command.id(); brainGeneration++;
                        }
                    }
                    return result;
                }
                Thread.sleep(10);
            }
            throw new IOException("model command timeout; save not confirmed");
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CompletionException(failure);
        } finally {
            for (Path path : new Path[]{temporary, request, response}) try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }
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

    private Response exchange(String id, String payload, PolicyFrame frame, long generation) {
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
                    synchronized (identityLock) {
                        if (brainGeneration != generation) throw new IOException("policy response predates model load");
                        if (expectedBrainId != null && (result.metadata() == null || !expectedBrainId.equals(result.metadata().brainId())))
                            throw new IOException("policy response belongs to another brain");
                        if (result.metadata() != null) expectedBrainId = result.metadata().brainId();
                    }
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
