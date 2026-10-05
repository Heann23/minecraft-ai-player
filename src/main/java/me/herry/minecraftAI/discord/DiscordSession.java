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
    public record PersonalReply(String user, long revision, String text) {}
    public record Status(boolean closed, int users, int queuedEvents, long processedInputs, long droppedEvents, long diagnosticFailures,
                         boolean memoryFailure, long memoryRevision, boolean memoryRecovered, long memoryRecoveredAt, int rejectedRecoveryPoints) {}
    private final DiscordSettings settings;
    private final DiscordMemoryStore store;
    private final ConversationTurns turns;
    private final ConversationMemory memory;
    private final VoiceIngress ingress;
    private final SpeechRecognitionWorker speech;
    private final PcmPlayback playback;
    private final ResponsePipeline responses;
    private final DiscordTextConversation textConversation;
    private final Consumer<String> diagnostic;
    private final ThreadPoolExecutor events;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong diagnosticFailures = new AtomicLong();
    private final AtomicLong processedInputs = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger pendingRecognitions = new java.util.concurrent.atomic.AtomicInteger();
    private final Object eventGate = new Object();
    private final Set<CompletableFuture<Void>> pendingEvents = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Set<String> participants = Set.of();
    private Map<String, String> names = Map.of();
    private volatile int userCount;
    private boolean memoryMaintenance;
    private final JoinGreetings greetings;
    private final LongSupplier monotonicMillis, wallClock;
    private long lastHumanSpeech;

    /** Takes ownership of the memory store and providers' tasks. The owner closes the session on shutdown. */
    public DiscordSession(DiscordSettings settings, DiscordMemoryStore store, SpeechRecognitionWorker.Recognizer recognizer,
                          ResponsePipeline.Model model, ResponsePipeline.Voice voice, Consumer<String> diagnostic,
                          LongSupplier wallClock, LongSupplier monotonicMillis, boolean automaticTick) {
        this(settings, store, recognizer, model, voice, diagnostic, wallClock, monotonicMillis, automaticTick, VoiceIngress.Policy.defaults());
    }
    public DiscordSession(DiscordSettings settings, DiscordMemoryStore store, SpeechRecognitionWorker.Recognizer recognizer,
                          ResponsePipeline.Model model, ResponsePipeline.Voice voice, Consumer<String> diagnostic,
                          LongSupplier wallClock, LongSupplier monotonicMillis, boolean automaticTick, VoiceIngress.Policy capturePolicy) {
        this(settings, store, recognizer, model, voice, diagnostic, wallClock, monotonicMillis, automaticTick, capturePolicy, false);
    }
    public DiscordSession(DiscordSettings settings, DiscordMemoryStore store, SpeechRecognitionWorker.Recognizer recognizer,
                          ResponsePipeline.Model model, ResponsePipeline.Voice voice, Consumer<String> diagnostic,
                          LongSupplier wallClock, LongSupplier monotonicMillis, boolean automaticTick, VoiceIngress.Policy capturePolicy, boolean greetOnJoin) {
        this.settings = java.util.Objects.requireNonNull(settings); this.store = java.util.Objects.requireNonNull(store);
        java.util.Objects.requireNonNull(recognizer); java.util.Objects.requireNonNull(model); java.util.Objects.requireNonNull(voice);
        java.util.Objects.requireNonNull(wallClock); java.util.Objects.requireNonNull(monotonicMillis); java.util.Objects.requireNonNull(diagnostic);
        this.wallClock = wallClock; this.monotonicMillis = monotonicMillis; lastHumanSpeech = monotonicMillis.getAsLong();
        greetings = new JoinGreetings(JoinGreetings.Policy.defaults(greetOnJoin));
        if (settings.guildId().isEmpty()) throw new IllegalArgumentException("Discord session requires configured guild");
        this.diagnostic = code -> { try { diagnostic.accept(code); } catch (RuntimeException failedSink) { diagnosticFailures.incrementAndGet(); } };
        turns = new ConversationTurns(wallClock, settings.followupMillis(), settings.contextLines());
        memory = new ConversationMemory(turns, store); ingress = new VoiceIngress(java.util.Objects.requireNonNull(capturePolicy), monotonicMillis);
        playback = new PcmPlayback(); responses = new ResponsePipeline(turns, model, voice, playback, this.diagnostic);
        textConversation = new DiscordTextConversation(settings, store, model, wallClock, this::beforeTextPreference);
        events = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), r -> daemon(r, "MinecraftAI-discord-events"));
        speech = new SpeechRecognitionWorker(ingress, recognizer, result -> {
            pendingRecognitions.incrementAndGet();
            post(() -> recognized(result)).whenComplete((ignored, error) -> {
                pendingRecognitions.decrementAndGet(); if (error == null) processedInputs.incrementAndGet();
            });
        }, this.diagnostic, monotonicMillis, 15_000);
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
                greetings.participants(next, monotonicMillis.getAsLong());
            }
            names = aliases;
            return done();
        });
    }

    /** Fast network entry point: copy one frame, enqueue, and return. No provider or disk work here. */
    public CompletableFuture<Void> audio(String userId, byte[] pcm, boolean speechDetected, boolean continuationExpected) {
        PcmAudio.requireFrame(pcm); byte[] owned = pcm.clone();
        return post(() -> {
            if (!memoryMaintenance) {
                if (speechDetected && participants.contains(userId)) lastHumanSpeech = monotonicMillis.getAsLong();
                captured(ingress.frame(userId, owned, speechDetected, continuationExpected));
            }
            return done();
        });
    }
    public CompletableFuture<Void> tick() { return post(() -> {
        if (!memoryMaintenance) { captured(ingress.tick()); greetIfReady(); }
        return done();
    }); }
    private void greetIfReady() {
        String user = greetings.claim(monotonicMillis.getAsLong(), lastHumanSpeech,
                turns.busy() || ingress.capturing() || speech.busy() || pendingRecognitions.get() > 0);
        if (user == null) return;
        var token = turns.beginGreeting(user); if (token == null) return;
        var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), user);
        store.visible(subject, participants).whenComplete((facts, error) -> post(() -> {
            if (turns.isCurrent(token)) {
                if (error != null || !responses.greet(token, JoinGreetings.text(subject, facts, wallClock.getAsLong()))) {
                    turns.finish(token); diagnostic.accept("discord-greeting-failed");
                }
            }
            return done();
        }));
    }

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
        greetings.dismiss(user, monotonicMillis.getAsLong());
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
        return post(() -> {
            if (memoryMaintenance && !value) throw new IllegalStateException("memory restore in progress");
            if (value) greetings.clearPending();
            turns.quiet(value); responses.cancel(); ingress.reset(); speech.refreshRoutes(); return done();
        });
    }
    public CompletableFuture<Void> forget(String userId) {
        var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), userId);
        return post(() -> {
            if (memoryMaintenance) throw new IllegalStateException("memory restore in progress");
            greetings.clearPending();
            textConversation.forget(userId);
            ingress.reset(); speech.refreshRoutes(); turns.cancelCurrent(); responses.cancel();
            return memory.forget(subject);
        });
    }
    /** Scope is a fixed personal command choice, never another user's identity or arbitrary memory label. */
    public CompletableFuture<Void> forgetPreference(String userId, String scope) {
        var target = switch (java.util.Objects.requireNonNull(scope)) {
            case "all" -> ConfirmedTextForget.Target.ALL;
            case "name" -> ConfirmedTextForget.Target.NAME;
            case "speech" -> ConfirmedTextForget.Target.SPEECH;
            case "joke" -> ConfirmedTextForget.Target.JOKE;
            default -> throw new IllegalArgumentException("personal deletion scope");
        };
        if (target == ConfirmedTextForget.Target.ALL) return forget(userId);
        var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), userId);
        var key = target.key(subject);
        return post(() -> {
            if (memoryMaintenance) throw new IllegalStateException("memory restore in progress");
            greetings.clearPending(); textConversation.forget(userId);
            ingress.reset(); speech.refreshRoutes(); responses.cancel();
            return memory.forget(key);
        });
    }
    /** Explicit personal slash input, independent of uncertain voice recognition or generated text. */
    public CompletableFuture<Void> confirmedName(String userId, String name, String interactionId) {
        if (name == null || !name.matches("[가-힣A-Za-z]{1,20}")) throw new IllegalArgumentException("confirmed name");
        return confirmedFact(userId, DiscordMemory.Kind.NAME, "preferred", name, interactionId);
    }
    public CompletableFuture<Void> confirmedSpeechStyle(String userId, boolean allowed, String interactionId) {
        return confirmedFact(userId, DiscordMemory.Kind.SPEECH_AGREEMENT, "casual", allowed ? "ALLOWED" : "REFUSED", interactionId);
    }
    public CompletableFuture<Void> confirmedJokes(String userId, boolean allowed, String interactionId) {
        return confirmedFact(userId, DiscordMemory.Kind.AVOID_JOKE, "all", allowed ? "ALLOWED" : "AVOID", interactionId);
    }
    public CompletableFuture<PersonalReply> personalSettings(String user) {
        var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), user);
        return memoryOperation(store::snapshot).thenApply(snapshot -> new PersonalReply(user, snapshot.revision(),
                DiscordPersonalSettings.describe(subject, snapshot, wallClock.getAsLong())));
    }
    /** Any memory update invalidates a queued settings reply, including deletion and restore. */
    public boolean personalCurrent(PersonalReply reply) { return !closed.get() && store.status().revision() == reply.revision(); }
    private CompletableFuture<Void> confirmedFact(String userId, DiscordMemory.Kind kind, String label, String value, String interactionId) {
        var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), userId);
        var key = new DiscordMemory.Key(subject, kind, "", label);
        if (interactionId == null || !interactionId.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("confirmation source");
        return post(() -> {
            if (memoryMaintenance) throw new IllegalStateException("memory restore in progress");
            greetings.clearPending();
            textConversation.forget(userId);
            ingress.reset(); speech.refreshRoutes(); turns.forget(userId); responses.cancel();
            return store.remember(key, value, DiscordMemory.Evidence.EXPLICIT, "slash-" + interactionId, 0).thenApply(snapshot -> null);
        });
    }
    private CompletableFuture<Void> beforeTextPreference(String user, java.util.function.BooleanSupplier current) {
        return post(() -> {
            if (!current.getAsBoolean()) return done();
            if (memoryMaintenance) throw new IllegalStateException("memory restore in progress");
            greetings.clearPending(); ingress.reset(); speech.refreshRoutes(); turns.forget(user); responses.cancel();
            return done();
        });
    }
    public CompletableFuture<String> backup() {
        return memoryOperation(() -> store.backup().thenApply(path -> path == null ? null : path.getFileName().toString()));
    }
    public CompletableFuture<List<String>> backupIds() { return memoryOperation(store::backupIds); }
    private <T> CompletableFuture<T> memoryOperation(Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        post(() -> {
            if (memoryMaintenance) throw new IllegalStateException("memory restore in progress");
            return operation.get().thenAccept(result::complete);
        }).whenComplete((ignored, error) -> { if (error != null) result.completeExceptionally(error); });
        return result;
    }
    /** Transport pauses first and stays paused after either success or failure. */
    public CompletableFuture<Void> restoreBackup(String identifier) {
        MemoryFiles.requireBackupName(identifier);
        return post(() -> {
            if (memoryMaintenance) throw new IllegalStateException("memory restore in progress");
            memoryMaintenance = true; textConversation.reset(); greetings.clearPending(); turns.quiet(true); turns.resetContext(); responses.cancel(); ingress.reset(); speech.refreshRoutes();
            CompletableFuture<Void> result = new CompletableFuture<>();
            store.restoreBackup(identifier).whenComplete((snapshot, error) -> post(() -> {
                memoryMaintenance = false;
                if (error == null) result.complete(null); else result.completeExceptionally(error);
                return done();
            }).whenComplete((ignored, failure) -> { if (failure != null) result.completeExceptionally(failure); }));
            return result;
        });
    }
    public PcmPlayback.Frame nextFrame() { return playback.nextFrame(); }
    /** Drops only temporary private text state; the persistent store and shared voice context are untouched. */
    public CompletableFuture<Void> resetText(String user) {
        new DiscordMemory.Subject(settings.guildId(), settings.characterId(), user);
        return post(() -> { textConversation.forget(user); return done(); });
    }
    public CompletableFuture<Boolean> cancelText(String user) {
        new DiscordMemory.Subject(settings.guildId(), settings.characterId(), user);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        post(() -> { result.complete(textConversation.cancelPending(user)); return done(); })
                .whenComplete((ignored, error) -> { if (error != null) result.completeExceptionally(error); });
        return result;
    }
    public CompletableFuture<DiscordTextConversation.Reply> textReply(String user, String text, String interaction) {
        CompletableFuture<DiscordTextConversation.Reply> result = new CompletableFuture<>();
        var active = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<DiscordTextConversation.Reply>>();
        result.whenComplete((reply, error) -> {
            if (result.isCancelled()) { var pending = active.get(); if (pending != null) pending.cancel(true); }
        });
        post(() -> {
            if (result.isCancelled()) return done();
            if (memoryMaintenance) throw new IllegalStateException("memory restore in progress");
            var pending = textConversation.reply(user, text, interaction); active.set(pending);
            if (result.isCancelled()) pending.cancel(true);
            return pending.thenAccept(reply -> { if (!result.complete(reply)) textConversation.discard(reply); });
        }).whenComplete((ignored, error) -> {
            if (error == null) return;
            var cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
            if (cause instanceof java.util.concurrent.CancellationException) result.cancel(false);
            else result.completeExceptionally(error);
        });
        return result;
    }
    public boolean textCurrent(DiscordTextConversation.Reply reply) { return textConversation.current(reply); }
    public DiscordTextConversation.Status textStatus() { return textConversation.status(); }
    public void textSubmitted(DiscordTextConversation.Reply reply) { textConversation.submitted(reply); }
    public void textDiscard(DiscordTextConversation.Reply reply) { textConversation.discard(reply); }
    public boolean submitted(PcmPlayback.Frame frame) { return playback.submitted(frame); }
    public Status status() {
        var memoryStatus = store.status();
        return new Status(closed.get(), userCount, events.getQueue().size(), processedInputs.get(), dropped.get(), diagnosticFailures.get(), store.hasFailure(),
                memoryStatus.revision(), memoryStatus.recovered(), memoryStatus.recoveredAt(), memoryStatus.rejectedRecoveryPoints());
    }

    private void captured(VoiceIngress.Events captured) {
        for (VoiceIngress.Onset onset : captured.onsets()) { responses.interrupt(onset.route().userId()); speech.refreshRoutes(); }
        for (VoiceIngress.Utterance utterance : captured.completed()) speech.submit(utterance);
    }
    static List<ConversationTurns.Line> boundedContext(List<ConversationTurns.Line> context) {
        int first = context.size(), size = 0;
        while (first > 0 && size + context.get(first - 1).text().length() <= 16_000) size += context.get(--first).text().length();
        return List.copyOf(context.subList(first, context.size()));
    }
    static List<DiscordMemory.Fact> boundedMemory(List<DiscordMemory.Fact> facts) {
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
        textConversation.close(); responses.close(); playback.close(); speech.close();
        for (Runnable task : events.shutdownNow()) if (task instanceof DiscordSession.Event event) event.result.cancel(false);
        pendingEvents.forEach(result -> result.cancel(false)); store.close();
    }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
}
