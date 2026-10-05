package me.herry.minecraftAI.discord;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Private, bounded text context per user. Reuses dialogue and confirmed memory; no speech or game access. */
public final class DiscordTextConversation implements AutoCloseable {
    public record Reply(ConversationTurns.Token turn, String text) {}
    private final DiscordSettings settings;
    private final DiscordMemoryStore store;
    private final ResponsePipeline.Model model;
    private final LongSupplier clock;
    private final LinkedHashMap<String, ConversationTurns> contexts = new LinkedHashMap<>();
    private final Set<CompletableFuture<Reply>> pending = ConcurrentHashMap.newKeySet();
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
            if (contexts.size() == 32) { var oldest = contexts.firstEntry(); contexts.remove(oldest.getKey()); oldest.getValue().close(); }
            turns = new ConversationTurns(clock, settings.followupMillis(), settings.contextLines()); turns.join(user); contexts.put(user, turns);
        }
        var accepted = turns.accept(user, interaction, text.strip(), ConversationTurns.Address.CHARACTER, false);
        if (accepted.token() == null) return CompletableFuture.failedFuture(new IllegalStateException("duplicate text input"));
        var result = new CompletableFuture<Reply>(); pending.add(result); result.whenComplete((reply, error) -> pending.remove(result));
        var conversation = turns;
        try { worker.execute(() -> generate(user, conversation, accepted.token(), result)); }
        catch (RejectedExecutionException full) { turns.finish(accepted.token()); result.completeExceptionally(new IllegalStateException("text conversation busy")); }
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
        catch (Exception failure) { turns.finish(token); result.completeExceptionally(new IllegalStateException("text conversation unavailable")); }
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
    public synchronized void forget(String user) { var turns = contexts.remove(user); if (turns != null) turns.close(); }
    public synchronized void reset() { contexts.values().forEach(ConversationTurns::close); contexts.clear(); }
    @Override public synchronized void close() { if (closed) return; closed = true; reset(); worker.shutdownNow(); pending.forEach(reply -> reply.cancel(false)); }
}
