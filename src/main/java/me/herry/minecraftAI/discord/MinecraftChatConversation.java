package me.herry.minecraftAI.discord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import me.herry.minecraftAI.ai.comm.CommunicationHub.Dialogue.Stage;
import me.herry.minecraftAI.ai.comm.IncomingMessage;
import me.herry.minecraftAI.discord.MinecraftChatRelay.Speaker;

/**
 * Herry's side of the Minecraft chat. Lines the game chat relay offers are answered by the same dialogue model and memory as
 * voice, as typed text. Every player has a private bounded context; a short shared log of what was said to and by Herry in chat
 * gives chat and voice answers the public conversation. Typed text is reliable, so names and settings are stored without an
 * echoed question, but only where a player's name proves who they are. No Bukkit access and no game commands.
 */
final class MinecraftChatConversation implements MinecraftChatRelay.Lane, AutoCloseable {
    static final String UNVERIFIED = "이 서버에서는 채팅으로 이름·말투·기억 설정을 바꿀 수 없어요. 디스코드에서 음성이나 /herry 명령으로 정해 주세요.";
    static final String UNAVAILABLE = "지금은 대답이 어려워요. 잠시 뒤에 다시 말해 주세요.";
    /** Shown to the model as the speaker of a chat line; a different shape from every user id, so it never selects the current input. */
    static final String CHAT = "게임 채팅";
    private static final int USERS = 32, SHARED_LINES = 12, MAX_INPUT = 256, MAX_REPLY = 240, FAILURES_TO_PAUSE = 3;
    private static final long SHARED_MILLIS = 600_000, ANSWER_MILLIS = 20_000, FOLLOWUP_MILLIS = 30_000, COOLDOWN_MILLIS = 1_500, PAUSE_MILLIS = 60_000;

    private record Work(ConversationTurns turns, ConversationTurns.Token token, FutureTask<Void> task) {}
    private final DiscordSettings settings;
    private final DiscordMemoryStore store;
    private final ResponsePipeline.Model model;
    private final String target;
    private final MinecraftChatRelay relay;
    private final Consumer<String> diagnostic;
    private final LongSupplier wallClock, monotonic;
    private final LinkedHashMap<String, ConversationTurns> contexts = new LinkedHashMap<>();
    private final Map<String, Work> work = new HashMap<>();
    private final Map<String, Long> lastOffer = new HashMap<>();
    private final ArrayDeque<ConversationTurns.Line> shared = new ArrayDeque<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), task -> { var thread = new Thread(task, "MinecraftAI-chat-dialogue"); thread.setDaemon(true); return thread; });
    private String engaged;
    private long engagedUntil, sequence, pausedUntil;
    private int failures;
    private boolean maintenance, closed;

    /** Uses the store and model of the voice session without owning them; close this before they are closed. */
    MinecraftChatConversation(DiscordSettings settings, DiscordMemoryStore store, ResponsePipeline.Model model, String target,
                              MinecraftChatRelay relay, Consumer<String> diagnostic, LongSupplier wallClock, LongSupplier monotonic) {
        this.settings = java.util.Objects.requireNonNull(settings); this.store = java.util.Objects.requireNonNull(store);
        this.model = java.util.Objects.requireNonNull(model); this.relay = java.util.Objects.requireNonNull(relay);
        this.diagnostic = java.util.Objects.requireNonNull(diagnostic);
        this.wallClock = java.util.Objects.requireNonNull(wallClock); this.monotonic = java.util.Objects.requireNonNull(monotonic);
        if (DiscordGameState.target(target).isEmpty()) throw new IllegalArgumentException("chat dialogue needs the game AI it speaks as");
        this.target = target;
    }

    @Override public String target() { return target; }
    /** The same call forms as voice. The hub additionally accepts the AI's Minecraft name anywhere in the line. */
    @Override public String called(String text) { return text != null && CallWord.called(text) ? CallWord.body(text) : null; }

    /** Only the player Herry answered last, only for a short while, and only until someone else writes a line. */
    @Override public synchronized boolean following(Speaker speaker) {
        if (closed || maintenance || engaged == null) return false;
        if (engaged.equals(speaker.id()) && monotonic.getAsLong() < engagedUntil) return true;
        engaged = null; return false;
    }

    /** Server thread: decide now, answer later. A refused line gets the rule-based answer instead. */
    @Override public synchronized boolean offer(Speaker speaker, String body, Stage stage, IncomingMessage origin) {
        if (closed || maintenance || body == null || origin == null) return false;
        // A bare call ("해리야") has no body; the line itself is what the player said.
        String said = body.isBlank() ? origin.text().strip() : body.strip();
        if (said.isEmpty() || said.length() > MAX_INPUT) return false;
        boolean personal = ConfirmedTextForget.read(said) != null || ConfirmedTextPreference.read(said) != null;
        if (stage == Stage.PERSONAL && !personal) return false;
        long now = monotonic.getAsLong();
        if (!personal) {
            // Only model answers are limited: a setting is cheap and must never fall through to the game rules.
            if (now < pausedUntil) return false;
            Long last = lastOffer.get(speaker.id());
            if (last != null && now - last < COOLDOWN_MILLIS) return false;
        }
        if (worker.getQueue().remainingCapacity() == 0) return false;
        var turns = contexts.get(speaker.id());
        if (turns == null) {
            if (contexts.size() >= USERS) drop(contexts.keySet().iterator().next());
            turns = new ConversationTurns(wallClock, settings.followupMillis(), settings.contextLines());
            turns.join(speaker.id()); contexts.put(speaker.id(), turns);
        }
        var accepted = turns.accept(speaker.id(), "c" + ++sequence, said, ConversationTurns.Address.CHARACTER, false);
        if (accepted.token() == null) return false;
        cancelWork(speaker.id());
        var conversation = turns; var token = accepted.token(); long deadline = now + ANSWER_MILLIS;
        var task = new FutureTask<Void>(() -> { generate(speaker, said, personal, conversation, token, origin, deadline); return null; });
        work.put(speaker.id(), new Work(turns, token, task));
        try { worker.execute(task); }
        catch (RejectedExecutionException full) { work.remove(speaker.id()); turns.finish(token); return false; }
        if (!personal) {
            if (lastOffer.size() >= 256) lastOffer.clear();
            lastOffer.put(speaker.id(), now);
            remember(new ConversationTurns.Line(label(speaker), "Herry", said, false, wallClock.getAsLong()));
        }
        return true;
    }

    /** A rule-based answer or an announcement the game AI wrote in chat, so later talk knows about it. */
    @Override public synchronized void said(String text, Speaker to, String question) {
        if (closed || text == null || text.isBlank() || text.length() > 1000) return;
        long now = wallClock.getAsLong();
        if (to == null) {
            // Goal announcements repeat often; only the latest one stays so they cannot push the conversation out.
            var last = shared.peekLast();
            if (last != null && last.assistant() && last.target().equals(CHAT)) shared.removeLast();
            remember(new ConversationTurns.Line("Herry", CHAT, text, true, now));
            return;
        }
        if (question != null && !question.isBlank() && question.length() <= MAX_INPUT)
            remember(new ConversationTurns.Line(label(to), "Herry", question.strip(), false, now));
        remember(new ConversationTurns.Line("Herry", label(to), text, true, now));
        engaged = to.id(); engagedUntil = monotonic.getAsLong() + FOLLOWUP_MILLIS;
    }

    /** Recent public chat with Herry, oldest first, for a voice answer. Chat is visible to the whole server, so nothing private. */
    synchronized List<ConversationTurns.Line> recent() {
        long oldest = wallClock.getAsLong() - SHARED_MILLIS;
        shared.removeIf(line -> line.timeMillis() < oldest);
        return List.copyOf(shared);
    }
    /** Memory restore: answer nothing and forget every temporary context until it is over. */
    synchronized void maintenance(boolean value) {
        maintenance = value;
        if (value) { reset(); shared.clear(); engaged = null; }
    }

    private void generate(Speaker speaker, String input, boolean personal, ConversationTurns turns, ConversationTurns.Token token,
                          IncomingMessage origin, long deadline) {
        try {
            if (!turns.isCurrent(token)) return;
            var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), speaker.id());
            String answer = personal ? setting(speaker, subject, input, turns, token) : talk(speaker, subject, turns, token, deadline);
            if (answer == null) return;
            synchronized (this) { failures = 0; }
            send(speaker, turns, token, origin, chatLine(answer), personal, deadline);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); turns.finish(token); }
        catch (Exception failure) {
            boolean abandoned = !turns.isCurrent(token);
            synchronized (this) {
                if (closed || abandoned) return;
                if (++failures >= FAILURES_TO_PAUSE) { failures = 0; pausedUntil = monotonic.getAsLong() + PAUSE_MILLIS; }
            }
            report("minecraft-chat-dialogue-failed");
            // One fixed line instead of silence; it is not held to the answer deadline because the player is still waiting.
            send(speaker, turns, token, origin, UNAVAILABLE, true, Long.MAX_VALUE);
        } finally { synchronized (this) { var active = work.get(speaker.id()); if (active != null && active.token().equals(token)) work.remove(speaker.id()); } }
    }
    /** A whole typed sentence that changes or erases the writer's own setting. Answered with fixed text, never by the model. */
    private String setting(Speaker speaker, DiscordMemory.Subject subject, String input, ConversationTurns turns, ConversationTurns.Token token) throws Exception {
        var deletion = ConfirmedTextForget.read(input);
        var change = deletion == null ? ConfirmedTextPreference.read(input) : null;
        if (deletion == null && change == null) return null;
        // Where names can be faked, chat may only make Herry plainer for that name; nothing is named, loosened or erased.
        if (!speaker.verified() && !ConversationMemory.tightening(change)) return UNVERIFIED;
        CompletableFuture<DiscordMemory.Snapshot> saved;
        synchronized (this) {
            if (closed || !turns.isCurrent(token)) return null;
            if (deletion != null) {
                turns.clearContextFor(token);
                saved = deletion == ConfirmedTextForget.Target.ALL ? store.forget(subject) : store.forget(deletion.key(subject));
            } else saved = store.remember(new DiscordMemory.Key(subject, change.kind(), "", change.label()), change.value(),
                    DiscordMemory.Evidence.EXPLICIT, "chat-" + token.conversation() + "-" + token.turn(), 0);
        }
        saved.get(3, TimeUnit.SECONDS);
        boolean casual = DiscordPersonalSettings.casual(subject, store.visible(subject, Set.of(speaker.id())).get(3, TimeUnit.SECONDS), wallClock.getAsLong());
        return deletion != null ? deletion.reply(casual) : change.reply(casual);
    }
    private String talk(Speaker speaker, DiscordMemory.Subject subject, ConversationTurns turns, ConversationTurns.Token token, long deadline) throws Exception {
        var facts = store.visible(subject, Set.of(speaker.id())).get(3, TimeUnit.SECONDS);
        if (!turns.isCurrent(token)) return null;
        String answer = model.respond(new ResponsePipeline.Request(token, context(turns, speaker), DiscordSession.boundedMemory(facts), false,
                () -> turns.isCurrent(token) && monotonic.getAsLong() < deadline));
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("chat reply bounds");
        return answer;
    }
    /** The player's own lines last, so the current input stays the last line of this speaker; other players' chat goes before. */
    private List<ConversationTurns.Line> context(ConversationTurns turns, Speaker speaker) {
        String mine = label(speaker); var lines = new ArrayList<ConversationTurns.Line>();
        for (var line : recent()) if (!line.speaker().equals(mine) && !line.target().equals(mine)) lines.add(line);
        lines.addAll(turns.context());
        return DiscordSession.boundedContext(lines.size() > 128 ? lines.subList(lines.size() - 128, lines.size()) : lines);
    }
    private void send(Speaker speaker, ConversationTurns turns, ConversationTurns.Token token, IncomingMessage origin, String text,
                      boolean privateMatter, long deadline) {
        if (!turns.generated(token, text)) return;
        var reply = new MinecraftChatRelay.Reply(origin, text, () -> turns.isCurrent(token) && monotonic.getAsLong() < deadline,
                () -> delivered(speaker, turns, token, text, privateMatter));
        if (!relay.reply(reply)) { turns.finish(token); report("minecraft-chat-reply-dropped"); }
    }
    /** Server thread, right after the line went out to the chat. Only now does the answer count as said. */
    private synchronized void delivered(Speaker speaker, ConversationTurns turns, ConversationTurns.Token token, String text, boolean privateMatter) {
        if (turns.played(token, text.length())) turns.finish(token);
        if (closed) return;
        if (!privateMatter) remember(new ConversationTurns.Line("Herry", label(speaker), text, true, wallClock.getAsLong()));
        engaged = speaker.id(); engagedUntil = monotonic.getAsLong() + FOLLOWUP_MILLIS;
    }

    /** One chat line: no line breaks, and cut at a sentence end rather than in the middle when it is too long. */
    static String chatLine(String answer) {
        String line = answer.replaceAll("\\s+", " ").strip();
        if (line.length() <= MAX_REPLY) return line;
        var kept = new StringBuilder();
        for (String sentence : SentenceChunks.split(line)) {
            if (kept.length() + sentence.length() > MAX_REPLY) break;
            kept.append(sentence);
        }
        String cut = kept.toString().strip();
        return cut.isEmpty() ? line.substring(0, Character.isHighSurrogate(line.charAt(MAX_REPLY - 1)) ? MAX_REPLY - 1 : MAX_REPLY).strip() : cut;
    }
    private static String label(Speaker speaker) { return CHAT + " " + speaker.name(); }
    private void remember(ConversationTurns.Line line) {
        shared.addLast(line);
        while (shared.size() > SHARED_LINES) shared.removeFirst();
    }
    private void cancelWork(String user) {
        var active = work.remove(user);
        if (active == null) return;
        active.turns().finish(active.token()); active.task().cancel(true); worker.purge();
    }
    private void drop(String user) { var turns = contexts.remove(user); if (turns != null) turns.close(); cancelWork(user); }
    private void reset() {
        for (String user : List.copyOf(contexts.keySet())) drop(user);
        for (String user : List.copyOf(work.keySet())) cancelWork(user);
    }
    private void report(String code) { try { diagnostic.accept(code); } catch (RuntimeException sinkFailure) { /* a diagnostic sink must not break chat */ } }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; reset(); shared.clear(); engaged = null; worker.shutdownNow();
    }
}
