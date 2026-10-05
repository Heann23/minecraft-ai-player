package me.herry.minecraftAI.discord;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Private, bounded text context per user. Reuses dialogue and confirmed memory; no speech or game access. */
public final class DiscordTextConversation implements AutoCloseable {
    public record Reply(ConversationTurns.Token turn, String text) {}
    public record Status(boolean closed, boolean running, int queued, long completed, long cancelled, long failed, long rejected) {
        public String describe() {
            return "텍스트 대화: " + (closed ? "종료됨" : running ? "답변 준비 중" : "대기 중") + " · 대기 " + queued + "건"
                    + " · 완료 " + completed + " · 취소 " + cancelled + " · 실패 " + failed + " · 요청 초과 " + rejected;
        }
    }
    private record Work(ConversationTurns turns, ConversationTurns.Token token,
                        CompletableFuture<Reply> result, FutureTask<Void> task) {}
    private final DiscordSettings settings;
    private final DiscordMemoryStore store;
    private final ResponsePipeline.Model model;
    private final LongSupplier clock;
    private final LinkedHashMap<String, ConversationTurns> contexts = new LinkedHashMap<>();
    private final LinkedHashMap<String, Work> work = new LinkedHashMap<>();
    private final Set<CompletableFuture<Reply>> pending = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong completed = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong cancelled = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong failed = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rejected = new java.util.concurrent.atomic.AtomicLong();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), task -> { var thread = new Thread(task, "MinecraftAI-discord-text"); thread.setDaemon(true); return thread; });
    private boolean closed;
    public DiscordTextConversation(DiscordSettings settings, DiscordMemoryStore store, ResponsePipeline.Model model, LongSupplier clock) {
        this.settings = java.util.Objects.requireNonNull(settings); this.store = java.util.Objects.requireNonNull(store);
        this.model = java.util.Objects.requireNonNull(model); this.clock = java.util.Objects.requireNonNull(clock);
    }
    public synchronized CompletableFuture<Reply> reply(String user, String text, String interaction) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("text conversation closed"));
        new DiscordMemory.Subject(settings.guildId(), settings.characterId(), user);
        if (text == null || text.isBlank() || text.length() > 1000 || interaction == null || !interaction.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("text conversation input");
        var turns = contexts.get(user);
        if (turns == null) {
            if (contexts.size() == 32) { var oldest = contexts.firstEntry(); forget(oldest.getKey()); }
            turns = new ConversationTurns(clock, settings.followupMillis(), settings.contextLines()); turns.join(user); contexts.put(user, turns);
        }
        var accepted = turns.accept(user, interaction, text.strip(), ConversationTurns.Address.CHARACTER, false);
        if (accepted.token() == null) return CompletableFuture.failedFuture(new IllegalStateException("duplicate text input"));
        cancelWork(user);
        var result = new CompletableFuture<Reply>(); pending.add(result);
        var conversation = turns;
        var task = new FutureTask<Void>(() -> { generate(user, conversation, accepted.token(), result); return null; });
        work.put(user, new Work(turns, accepted.token(), result, task));
        result.whenComplete((reply, error) -> {
            pending.remove(result);
            if (result.isCancelled()) { cancelled.incrementAndGet(); cancel(user, conversation, accepted.token(), result); }
            else if (error == null) completed.incrementAndGet();
        });
        try { worker.execute(task); }
        catch (RejectedExecutionException full) {
            rejected.incrementAndGet();
            work.remove(user); turns.finish(accepted.token()); result.completeExceptionally(new IllegalStateException("text conversation busy"));
        }
        return result;
    }
    private void generate(String user, ConversationTurns turns, ConversationTurns.Token token, CompletableFuture<Reply> result) {
        try {
            if (!turns.isCurrent(token)) { result.cancel(false); return; }
            var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), user);
            var facts = store.visible(subject, Set.of(user)).get(3, TimeUnit.SECONDS);
            if (!turns.isCurrent(token)) { result.cancel(false); return; }
            String answer = model.respond(new ResponsePipeline.Request(token, DiscordSession.boundedContext(turns.context()), DiscordSession.boundedMemory(facts), false, () -> turns.isCurrent(token)));
            if (answer == null || answer.isBlank() || answer.length() > 1900) throw new IllegalArgumentException("text reply bounds");
            if (!turns.generated(token, answer)) { result.cancel(false); return; }
            result.complete(new Reply(token, answer));
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); turns.finish(token); result.cancel(false); }
        catch (Exception failure) {
            turns.finish(token);
            if (result.completeExceptionally(new IllegalStateException("text conversation unavailable"))) failed.incrementAndGet();
        }
        finally { synchronized (this) { var active = work.get(user); if (active != null && active.result() == result) work.remove(user); } }
    }
    private synchronized void cancel(String user, ConversationTurns turns, ConversationTurns.Token token, CompletableFuture<Reply> result) {
        turns.finish(token);
        var active = work.get(user);
        if (active != null && active.result() == result) cancelWork(user);
    }
    private void cancelWork(String user) {
        var active = work.remove(user);
        if (active == null) return;
        active.turns().finish(active.token()); active.task().cancel(true); active.result().cancel(false);
        worker.purge();
    }
    public synchronized boolean current(Reply reply) {
        var turns = contexts.get(reply.turn().userId()); return !closed && turns != null && turns.isCurrent(reply.turn());
    }
    /** Called only after Discord confirms this private message was delivered. */
    public synchronized void submitted(Reply reply) {
        var turns = contexts.get(reply.turn().userId());
        if (turns != null && turns.played(reply.turn(), reply.text().length())) turns.finish(reply.turn());
    }
    public synchronized void discard(Reply reply) {
        var turns = contexts.get(reply.turn().userId()); if (turns != null) turns.finish(reply.turn());
    }
    public synchronized void forget(String user) { var turns = contexts.remove(user); if (turns != null) turns.close(); cancelWork(user); }
    public synchronized void reset() {
        contexts.values().forEach(ConversationTurns::close); contexts.clear();
        for (String user : java.util.List.copyOf(work.keySet())) cancelWork(user);
    }
    /** Aggregate work counters only; no user identifiers, names, inputs or model output. Completion precedes Discord delivery. */
    public synchronized Status status() {
        return new Status(closed, worker.getActiveCount() > 0, worker.getQueue().size(), completed.get(), cancelled.get(), failed.get(), rejected.get());
    }
    @Override public synchronized void close() { if (closed) return; closed = true; reset(); worker.shutdownNow(); pending.forEach(reply -> reply.cancel(false)); }
}
