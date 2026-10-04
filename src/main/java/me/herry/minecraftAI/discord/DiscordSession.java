package me.herry.minecraftAI.discord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One channel's event lane, connecting fake or local STT/model/TTS providers without Bukkit access. */
public final class DiscordSession implements AutoCloseable {
    public record Status(boolean closed, int users, int queuedEvents, long processedInputs, long droppedEvents, long diagnosticFailures, boolean memoryFailure) {}
    private final DiscordSettings settings;
    private final DiscordMemoryStore store;
    private final ConversationTurns turns;
    private final ConversationMemory memory;
    private final VoiceIngress ingress;
    private final SpeechRecognitionWorker speech;
    private final PcmPlayback playback;
    private final ResponsePipeline responses;
    private final Consumer<String> diagnostic;
    private final ThreadPoolExecutor events;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong diagnosticFailures = new AtomicLong();
    private final AtomicLong processedInputs = new AtomicLong();
    private final Object eventGate = new Object();
    private final Set<CompletableFuture<Void>> pendingEvents = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Set<String> participants = Set.of();
    private Map<String, String> names = Map.of();
    private volatile int userCount;

    /** Takes ownership of the memory store and providers' tasks. The owner closes the session on shutdown. */
    public DiscordSession(DiscordSettings settings, DiscordMemoryStore store, SpeechRecognitionWorker.Recognizer recognizer,
                          ResponsePipeline.Model model, ResponsePipeline.Voice voice, Consumer<String> diagnostic,
                          LongSupplier wallClock, LongSupplier monotonicMillis, boolean automaticTick) {
        this.settings = java.util.Objects.requireNonNull(settings); this.store = java.util.Objects.requireNonNull(store);
        java.util.Objects.requireNonNull(recognizer); java.util.Objects.requireNonNull(model); java.util.Objects.requireNonNull(voice);
        java.util.Objects.requireNonNull(wallClock); java.util.Objects.requireNonNull(monotonicMillis); java.util.Objects.requireNonNull(diagnostic);
        if (settings.guildId().isEmpty()) throw new IllegalArgumentException("Discord session requires configured guild");
        this.diagnostic = code -> { try { diagnostic.accept(code); } catch (RuntimeException failedSink) { diagnosticFailures.incrementAndGet(); } };
        turns = new ConversationTurns(wallClock, settings.followupMillis(), settings.contextLines());
        memory = new ConversationMemory(turns, store); ingress = new VoiceIngress(VoiceIngress.Policy.defaults(), monotonicMillis);
        playback = new PcmPlayback(); responses = new ResponsePipeline(turns, model, voice, playback, this.diagnostic);
        events = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), r -> daemon(r, "MinecraftAI-discord-events"));
        speech = new SpeechRecognitionWorker(ingress, recognizer, result -> post(() -> recognized(result))
                .whenComplete((ignored, error) -> { if (error == null) processedInputs.incrementAndGet(); }), this.diagnostic, monotonicMillis, 15_000);
        timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "MinecraftAI-discord-voice-tick"));
        if (automaticTick) timer.scheduleWithFixedDelay(this::tick, 50, 50, TimeUnit.MILLISECONDS);
    }

    /** Transport supplies humans only; display names are candidate aliases, never authority or persistent names. */
    public CompletableFuture<Void> participants(Set<String> humanIds, Map<String, String> displayNames) {
        Set<String> next = Set.copyOf(humanIds); Map<String, String> aliases = Map.copyOf(displayNames);
        if (next.size() > 8 || next.stream().anyMatch(id -> id.isBlank() || id.length() > 100 || id.contains(":"))
                || aliases.size() > 32 || aliases.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getKey().length() > 100 || !next.contains(e.getValue())))
            throw new IllegalArgumentException("Discord participant metadata");
        return post(() -> {
            if (!next.equals(participants)) {
                for (String user : participants) if (!next.contains(user)) turns.leave(user);
                for (String user : next) if (!participants.contains(user)) turns.join(user);
                turns.cancelCurrent(); responses.cancel();
                ingress.participants(next); speech.refreshRoutes();
                participants = next; userCount = next.size();
            }
            names = aliases;
            return done();
        });
    }

    /** Fast network entry point: copy one frame, enqueue, and return. No provider or disk work here. */
    public CompletableFuture<Void> audio(String userId, byte[] pcm, boolean speechDetected, boolean continuationExpected) {
        PcmAudio.requireFrame(pcm); byte[] owned = pcm.clone();
        return post(() -> { captured(ingress.frame(userId, owned, speechDetected, continuationExpected)); return done(); });
    }
    public CompletableFuture<Void> tick() { return post(() -> { captured(ingress.tick()); return done(); }); }

    /** Always called on the event lane; the earlier STT worker check is insufficient. */
    private CompletableFuture<Void> recognized(SpeechRecognitionWorker.Result result) {
        if (!ingress.valid(result.route())) return done();
        String user = result.route().userId(), text = result.recognition().text();
        String utterance = result.route().session() + "-" + result.route().generation() + "-" + result.route().utterance();
        var address = AddresseeResolver.resolve(text, user, participants, names);
        boolean stop = ConversationStop.requested(text);
        var accepted = turns.accept(user, utterance, text, address.address(), stop);
        if (accepted.decision() == ConversationTurns.Decision.STOPPED) { responses.cancel(); return done(); }
        if (accepted.decision() != ConversationTurns.Decision.RESPOND) return done();
        var token = accepted.token(); var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), user);
        var saved = memory.capture(token, subject, text, result.recognition().reliableFinal(), utterance);
        Set<String> members = participants;
        CompletableFuture<Void> completed = new CompletableFuture<>();
        saved.thenCompose(ignored -> store.visible(subject, members)).whenComplete((facts, error) -> {
            if (error != null) {
                post(() -> { if (turns.isCurrent(token)) turns.finish(token); diagnostic.accept("conversation-memory-failed"); return done(); })
                        .whenComplete((ignored, failure) -> completed.complete(null));
                return;
            }
            post(() -> {
                if (ingress.valid(result.route()) && turns.isCurrent(token)) {
                    var request = new ResponsePipeline.Request(token, boundedContext(turns.context()), boundedMemory(facts));
                    if (!responses.respond(request)) turns.finish(token);
                }
                return done();
            }).whenComplete((ignored, failure) -> { if (failure == null) completed.complete(null); else completed.completeExceptionally(failure); });
        });
        return completed;
    }

    public CompletableFuture<Void> quiet(boolean value) {
        return post(() -> { turns.quiet(value); responses.cancel(); ingress.reset(); speech.refreshRoutes(); return done(); });
    }
    public CompletableFuture<Void> forget(String userId) {
        var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), userId);
        return post(() -> {
            ingress.reset(); speech.refreshRoutes(); turns.cancelCurrent(); responses.cancel();
            return memory.forget(subject);
        });
    }
    public PcmPlayback.Frame nextFrame() { return playback.nextFrame(); }
    public boolean submitted(PcmPlayback.Frame frame) { return playback.submitted(frame); }
    public Status status() { return new Status(closed.get(), userCount, events.getQueue().size(), processedInputs.get(), dropped.get(), diagnosticFailures.get(), store.hasFailure()); }

    private void captured(VoiceIngress.Events captured) {
        for (VoiceIngress.Onset onset : captured.onsets()) { responses.interrupt(onset.route().userId()); speech.refreshRoutes(); }
        for (VoiceIngress.Utterance utterance : captured.completed()) speech.submit(utterance);
    }
    private static List<ConversationTurns.Line> boundedContext(List<ConversationTurns.Line> context) {
        int first = context.size(), size = 0;
        while (first > 0 && size + context.get(first - 1).text().length() <= 16_000) size += context.get(--first).text().length();
        return List.copyOf(context.subList(first, context.size()));
    }
    private static List<DiscordMemory.Fact> boundedMemory(List<DiscordMemory.Fact> facts) {
        List<DiscordMemory.Fact> ordered = facts.stream().sorted(Comparator
                .comparingInt((DiscordMemory.Fact fact) -> priority(fact.key().kind()))
                .thenComparing(Comparator.comparingLong(DiscordMemory.Fact::recordedAt).reversed())).toList();
        List<DiscordMemory.Fact> selected = new ArrayList<>(); int size = 0;
        for (var fact : ordered) if (selected.size() < 64 && size + fact.value().length() <= 8000) { selected.add(fact); size += fact.value().length(); }
        return List.copyOf(selected);
    }
    private static int priority(DiscordMemory.Kind kind) {
        return switch (kind) { case NAME, SPEECH_AGREEMENT, AVOID_JOKE -> 0; case RELATION, ADDRESS_STYLE -> 1; case ITEM_STORY -> 2; };
    }
    private CompletableFuture<Void> post(Supplier<? extends CompletionStage<Void>> action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Discord session closed"));
        pendingEvents.add(result); result.whenComplete((ignored, error) -> pendingEvents.remove(result));
        Event task = new Event(action, result);
        try { events.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException e) { dropped.incrementAndGet(); result.completeExceptionally(e); }
        return result;
    }
    private final class Event implements Runnable {
        private final Supplier<? extends CompletionStage<Void>> action;
        private final CompletableFuture<Void> result;
        Event(Supplier<? extends CompletionStage<Void>> action, CompletableFuture<Void> result) { this.action = action; this.result = result; }
        @Override public void run() {
            synchronized (eventGate) {
                if (closed.get()) { result.cancel(false); return; }
                try { action.get().whenComplete((ignored, error) -> { if (error == null) result.complete(null); else result.completeExceptionally(error); }); }
                catch (RuntimeException e) { result.completeExceptionally(e); }
            }
        }
    }
    private static CompletableFuture<Void> done() { return CompletableFuture.completedFuture(null); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        timer.shutdownNow();
        synchronized (eventGate) { ingress.close(); turns.close(); userCount = 0; }
        responses.close(); playback.close(); speech.close();
        for (Runnable task : events.shutdownNow()) if (task instanceof DiscordSession.Event event) event.result.cancel(false);
        pendingEvents.forEach(result -> result.cancel(false)); store.close();
    }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
}
