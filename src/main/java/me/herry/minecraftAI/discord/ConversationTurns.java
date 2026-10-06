package me.herry.minecraftAI.discord;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Channel-local turn gate. No Bukkit objects, network, inference, or game commands. */
public final class ConversationTurns implements AutoCloseable {
    public enum Address { CHARACTER, OTHER_USER, UNKNOWN }
    public enum Decision { RESPOND, IGNORE, STOPPED }
    public record Token(UUID conversation, long turn, long generation, String userId) {}
    public record Accepted(Decision decision, Token token) {}
    public record Line(String speaker, String target, String text, boolean assistant, long timeMillis) {}
    private record Question(Token token, long nextPersonalTurn, long expiresAt) {}
    private record Confirmation(Token token, VoiceConfirmation.Proposal proposal, long nextPersonalTurn, long expiresAt) {}

    private final UUID conversation = UUID.randomUUID();
    private final LongSupplier clock;
    private final long followupMillis;
    private final int contextLimit;
    private final Set<String> members = new java.util.HashSet<>();
    private final Map<String, Long> engaged = new HashMap<>();
    private final Map<String, Long> personalTurns = new HashMap<>();
    private final Map<String, Long> seen = new LinkedHashMap<>();
    private final ArrayDeque<Line> context = new ArrayDeque<>();
    private long turn, generation;
    private Token current;
    private Question permissionQuestion;
    private Confirmation confirmation;
    private String generated = "";
    private String currentInput = "", currentUtterance = "";
    private int heardCharacters;
    private boolean closed, quiet, greeting;

    public ConversationTurns(LongSupplier clock, long followupMillis, int contextLimit) {
        if (followupMillis < 1 || contextLimit < 1 || contextLimit > 128) throw new IllegalArgumentException("turn limits");
        this.clock = java.util.Objects.requireNonNull(clock);
        this.followupMillis = followupMillis;
        this.contextLimit = contextLimit;
    }

    public synchronized void join(String userId) {
        ensureOpen();
        if (members.size() >= 64 && !members.contains(userId)) throw new IllegalStateException("participant capacity");
        if (members.add(id(userId))) {
            invalidate();
            // A newcomer did not hear the previous shared transcript.
            context.clear();
        }
    }

    public synchronized void leave(String userId) {
        members.remove(userId);
        personalTurns.remove(userId);
        engaged.remove(userId);
        if (permissionQuestion != null && permissionQuestion.token.userId.equals(userId)) permissionQuestion = null;
        if (confirmation != null && confirmation.token.userId.equals(userId)) confirmation = null;
        invalidate();
        // Session transcript must not retain a departing person's private context.
        context.clear();
    }

    /** Address resolution happens before this gate; style alone is never an explicit address. */
    public synchronized Accepted accept(String userId, String utteranceId, String text, Address address, boolean stop) {
        id(userId);
        id(utteranceId);
        java.util.Objects.requireNonNull(address);
        if (closed || !members.contains(userId)) return ignored();
        if (text == null || text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("utterance text");
        long now = clock.getAsLong();
        seen.entrySet().removeIf(e -> now - e.getValue() >= followupMillis);
        String request = userId + ":" + utteranceId;
        if (seen.containsKey(request) || seen.size() >= 512) return ignored();
        seen.put(request, now);
        if (address == Address.OTHER_USER) {
            engaged.remove(userId);
            if (current != null && current.userId.equals(userId)) invalidate();
            return ignored();
        }
        Long until = engaged.get(userId);
        boolean participating = until != null && now < until;
        if (address != Address.CHARACTER && (!participating || quiet)) return ignored();
        if (stop) {
            invalidate();
            engaged.remove(userId);
            permissionQuestion = null; confirmation = null;
            return new Accepted(Decision.STOPPED, null);
        }
        invalidate();
        engaged.put(userId, now + followupMillis);
        current = new Token(conversation, ++turn, generation, userId);
        currentInput = text; currentUtterance = utteranceId;
        long personalTurn = personalTurns.merge(userId, 1L, Long::sum);
        if (permissionQuestion != null && permissionQuestion.token.userId.equals(userId)
                && personalTurn > permissionQuestion.nextPersonalTurn) permissionQuestion = null;
        if (confirmation != null && confirmation.token.userId.equals(userId) && personalTurn > confirmation.nextPersonalTurn) confirmation = null;
        add(new Line(userId, "Herry", text, false, now));
        return new Accepted(Decision.RESPOND, current);
    }

    /** Called at speech onset, before STT finishes, to stop the current answer. */
    public synchronized boolean speechStarted(String userId) {
        if (greeting && !closed && members.contains(userId)) { invalidate(); return true; }
        Long until = engaged.get(userId);
        if (closed || !members.contains(userId) || until == null || clock.getAsLong() >= until) return false;
        invalidate();
        return true;
    }

    public synchronized boolean isCurrent(Token token) {
        return !closed && token != null && token.equals(current) && members.contains(token.userId);
    }

    public synchronized boolean matchesInput(Token token, String text, String utteranceId) {
        return isCurrent(token) && currentInput.equals(text) && currentUtterance.equals(utteranceId);
    }

    public synchronized boolean generated(Token token, String text) {
        if (!isCurrent(token)) return false;
        if (text == null || text.isBlank() || text.length() > 4000) throw new IllegalArgumentException("response text");
        if (!generated.isEmpty()) return false;
        generated = text;
        return true;
    }

    /** Playback adapter reports only the prefix actually played, never the generated whole answer. */
    public synchronized boolean played(Token token, int characters) {
        if (!isCurrent(token) || characters < heardCharacters || characters > generated.length()) return false;
        heardCharacters = characters;
        return true;
    }

    public synchronized void finish(Token token) {
        if (isCurrent(token)) invalidate();
    }

    public synchronized void cancelCurrent() { invalidate(); }
    public synchronized boolean busy() { return current != null; }
    /** A greeting never invents a user utterance or interrupts an existing response. */
    public synchronized Token beginGreeting(String userId) {
        if (closed || quiet || current != null || !members.contains(userId)) return null;
        current = new Token(conversation, ++turn, generation, userId); greeting = true;
        return current;
    }
    /** Only a fully submitted greeting opens a no-callword followup window. */
    public synchronized void greeted(Token token) {
        if (greeting && isCurrent(token) && !generated.isEmpty() && heardCharacters == generated.length())
            engaged.put(token.userId, clock.getAsLong() + followupMillis);
    }

    /** May only be registered after the entire permission question was actually heard. */
    public synchronized boolean askCasualPermission(Token token, long validMillis) {
        if (!isCurrent(token) || generated.isEmpty() || heardCharacters != generated.length() || validMillis < 1) return false;
        permissionQuestion = new Question(token, personalTurns.get(token.userId) + 1, clock.getAsLong() + validMillis);
        return true;
    }

    /** Explicit semantic answer supplied by a parser; guessing consent from style is forbidden. */
    public synchronized boolean answerCasualPermission(Token answer, boolean explicitAnswer) {
        if (!isCurrent(answer) || !explicitAnswer || permissionQuestion == null) return false;
        Question question = permissionQuestion;
        if (!question.token.userId.equals(answer.userId) || personalTurns.get(answer.userId) != question.nextPersonalTurn
                || clock.getAsLong() >= question.expiresAt) return false;
        permissionQuestion = null;
        return true;
    }

    /** May only be registered after the whole echo-back question was actually heard; the proposal lives in memory only. */
    synchronized boolean askConfirmation(Token token, VoiceConfirmation.Proposal proposal, long validMillis) {
        if (!isCurrent(token) || generated.isEmpty() || heardCharacters != generated.length() || validMillis < 1 || proposal == null) return false;
        confirmation = new Confirmation(token, proposal, personalTurns.get(token.userId) + 1, clock.getAsLong() + validMillis);
        return true;
    }

    /** The asked user's very next turn only. Returns the proposal being answered and consumes the question, or null. */
    synchronized VoiceConfirmation.Proposal answerConfirmation(Token answer) {
        if (!isCurrent(answer) || confirmation == null) return null;
        Confirmation question = confirmation;
        if (!question.token.userId.equals(answer.userId) || personalTurns.get(answer.userId) != question.nextPersonalTurn
                || clock.getAsLong() >= question.expiresAt) return null;
        confirmation = null;
        return question.proposal;
    }

    public synchronized void quiet(boolean value) {
        quiet = value;
        invalidate();
        engaged.clear();
        permissionQuestion = null; confirmation = null;
    }

    public synchronized List<Line> context() { return List.copyOf(context); }
    /** A confirmed deletion keeps its delivery token and deduplication, but no previous transcript. */
    public synchronized boolean clearContextFor(Token token) {
        if (!isCurrent(token)) return false;
        context.clear(); permissionQuestion = null; confirmation = null;
        return true;
    }
    /** Restore keeps membership and deduplication but retires every old answer and shared transcript. */
    public synchronized void resetContext() {
        invalidate(); engaged.clear(); permissionQuestion = null; confirmation = null; context.clear();
    }

    /** Deletion clears temporary context and invalidates pending provider output too. */
    public synchronized void forget(String userId) {
        invalidate();
        permissionQuestion = null; confirmation = null;
        engaged.remove(userId);
        context.clear();
    }

    @Override public synchronized void close() {
        invalidate();
        closed = true;
        members.clear();
        personalTurns.clear();
        engaged.clear();
        seen.clear();
        context.clear();
        permissionQuestion = null; confirmation = null;
    }

    private void invalidate() {
        if (current != null && heardCharacters > 0) add(new Line("Herry", current.userId, generated.substring(0, heardCharacters), true, clock.getAsLong()));
        current = null;
        greeting = false;
        generated = "";
        currentInput = ""; currentUtterance = "";
        heardCharacters = 0;
        generation++;
    }

    private void add(Line line) {
        context.addLast(line);
        while (context.size() > contextLimit) context.removeFirst();
    }

    private static Accepted ignored() { return new Accepted(Decision.IGNORE, null); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("conversation closed"); }
    private static String id(String value) {
        if (value == null || value.isBlank() || value.length() > 100 || value.indexOf(':') >= 0) throw new IllegalArgumentException("identifier");
        return value;
    }
}
