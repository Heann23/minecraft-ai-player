package me.herry.minecraftAI.discord;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One STT lane with a bounded backlog and route checks before and after inference. */
public final class SpeechRecognitionWorker implements AutoCloseable {
    public record Recognition(String text, boolean reliableFinal) {
        public Recognition {
            if (text == null || text.length() > 2000) throw new IllegalArgumentException("STT output bounds");
        }
    }
    public record Result(VoiceIngress.Route route, Recognition recognition) {}
    /** Providers must honor interruption and a finite execution deadline. */
    public interface Recognizer { Recognition recognize(byte[] mono16kLittleEndian, String language) throws Exception; }
    private final VoiceIngress ingress;
    private final Recognizer recognizer;
    private final Consumer<Result> result;
    private final Consumer<String> diagnostic;
    private final LongSupplier clock;
    private final long maxAge;
    private final ThreadPoolExecutor worker;
    private final java.util.Map<String, VoiceIngress.Route> admitted = new java.util.HashMap<>();
    private final java.util.Map<VoiceIngress.Route, Future<?>> tasks = new java.util.HashMap<>();
    private volatile boolean closed;

    public SpeechRecognitionWorker(VoiceIngress ingress, Recognizer recognizer, Consumer<Result> result,
                                    Consumer<String> diagnostic, LongSupplier clock, long maxAgeMillis) {
        this.ingress = Objects.requireNonNull(ingress); this.recognizer = Objects.requireNonNull(recognizer);
        // Receiver enqueues onto the session event lane and rechecks route validity there,
        // immediately before altering conversation state. It must not trust this worker's earlier check.
        this.result = Objects.requireNonNull(result); this.diagnostic = Objects.requireNonNull(diagnostic); this.clock = Objects.requireNonNull(clock);
        if (maxAgeMillis < 100 || maxAgeMillis > 60_000) throw new IllegalArgumentException("STT age limit");
        maxAge = maxAgeMillis;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), r -> {
            Thread thread = new Thread(r, "MinecraftAI-discord-STT"); thread.setDaemon(true); return thread;
        });
    }
    public synchronized Future<?> submit(VoiceIngress.Utterance utterance) {
        if (closed || !valid(utterance)) return java.util.concurrent.CompletableFuture.completedFuture(null);
        refreshRoutes();
        admitted.values().removeIf(route -> !ingress.valid(route));
        if (utterance.route().equals(admitted.get(utterance.route().userId()))) return java.util.concurrent.CompletableFuture.completedFuture(null);
        try {
            Future<?> task = worker.submit(() -> recognize(utterance));
            admitted.put(utterance.route().userId(), utterance.route()); tasks.put(utterance.route(), task); return task;
        }
        catch (java.util.concurrent.RejectedExecutionException e) {
            diagnostic.accept("stt-capacity"); return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
    }
    /** Invoke on new speech onset/leave/reset to retire invalid inference and queued candidates. */
    public synchronized void refreshRoutes() {
        var iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!ingress.valid(entry.getKey())) { entry.getValue().cancel(true); iterator.remove(); }
            else if (entry.getValue().isDone()) iterator.remove();
        }
        worker.purge();
    }
    public synchronized boolean busy() { return tasks.values().stream().anyMatch(task -> !task.isDone()); }
    private void recognize(VoiceIngress.Utterance utterance) {
        try {
            if (!valid(utterance)) return;
            Recognition output = recognizer.recognize(PcmAudio.mono16k(utterance.pcm()), "ko");
            if (!valid(utterance) || output == null || output.text.isBlank()) return;
            if (utterance.durationLimited()) output = new Recognition(output.text, false);
            result.accept(new Result(utterance.route(), output));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { if (valid(utterance)) diagnostic.accept("stt-provider-failed"); }
    }
    private boolean valid(VoiceIngress.Utterance utterance) {
        long age = clock.getAsLong() - utterance.endedAt();
        return !closed && !Thread.currentThread().isInterrupted() && ingress.valid(utterance.route()) && age >= 0 && age <= maxAge;
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; ingress.close(); admitted.clear();
        tasks.values().forEach(task -> task.cancel(true)); tasks.clear();
        for (Runnable queued : worker.shutdownNow()) if (queued instanceof Future<?> task) task.cancel(false);
    }
}
