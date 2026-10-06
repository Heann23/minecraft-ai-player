package me.herry.minecraftAI.discord;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static me.herry.minecraftAI.discord.DiscordMemory.*;

/** Connects confirmed current-user input to durable facts; never accepts generated model output. */
public final class ConversationMemory {
    private final ConversationTurns turns;
    private final DiscordMemoryStore store;

    public ConversationMemory(ConversationTurns turns, DiscordMemoryStore store) {
        this.turns = Objects.requireNonNull(turns); this.store = Objects.requireNonNull(store);
    }

    /** Caller supplies the final STT text for this exact accepted turn, not an interim transcription. */
    public CompletableFuture<Void> capture(ConversationTurns.Token token, Subject speaker,
                                           String text, boolean reliableFinal, String utteranceId) {
        // Admission and enqueue share the turn lock with deletion. A pre-deletion input
        // cannot be queued after its deletion and acquire a newer storage revision.
        synchronized (turns) {
            if (!reliableFinal || token == null || !token.userId().equals(speaker.userId()) || !turns.matchesInput(token, text, utteranceId))
                return CompletableFuture.completedFuture(null);
            var name = ConfirmedMemoryInput.introducedName(text, true);
            if (name.isPresent()) return store.remember(new Key(speaker, Kind.NAME, "", "preferred"),
                    name.get(), Evidence.EXPLICIT, utteranceId, 0).thenApply(snapshot -> null);
            var consent = ConfirmedMemoryInput.consent(text, true);
            if (consent.isPresent() && turns.answerCasualPermission(token, true))
                return store.remember(new Key(speaker, Kind.SPEECH_AGREEMENT, "", "casual"),
                        consent.get().name(), Evidence.EXPLICIT, utteranceId, 0).thenApply(snapshot -> null);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Only settings that remove jokes or casual speech. Misheard speech can at worst make Herry plainer, and a slash command or
     * text undoes it, so unlike names or consent these may come from STT that is not reliable. Loosening stays text and slash only.
     */
    static boolean tightening(ConfirmedTextPreference.Change change) {
        return change != null && ((change.kind() == Kind.AVOID_JOKE && change.value().equals("AVOID"))
                || (change.kind() == Kind.SPEECH_AGREEMENT && change.value().equals("REFUSED")));
    }
    /** Whole-utterance tightening request of the speaker of this accepted turn, stored under the turn lock; null if it is not one. */
    CompletableFuture<ConfirmedTextPreference.Change> captureTightening(ConversationTurns.Token token, Subject speaker, String text, String utteranceId) {
        if (text == null || text.indexOf('?') >= 0 || text.indexOf('？') >= 0) return null;
        var change = ConfirmedTextPreference.read(text);
        if (!tightening(change)) return null;
        synchronized (turns) {
            if (token == null || !token.userId().equals(speaker.userId()) || !turns.matchesInput(token, text, utteranceId)) return null;
            return store.remember(new Key(speaker, change.kind(), "", change.label()), change.value(), Evidence.EXPLICIT, utteranceId, 0)
                    .thenApply(snapshot -> change);
        }
    }

    /** Clear live model/playback routes and shared context before erasing durable memories. */
    public CompletableFuture<Void> forget(Subject person) {
        synchronized (turns) {
            turns.forget(person.userId());
            return store.forget(person).thenApply(snapshot -> null);
        }
    }
    /** Exact personal setting only; the same turn lock prevents pre-deletion speech from being enqueued later. */
    public CompletableFuture<Void> forget(Key key) {
        Objects.requireNonNull(key);
        String expected = switch (key.kind()) {
            case NAME -> "preferred";
            case SPEECH_AGREEMENT -> "casual";
            case AVOID_JOKE -> "all";
            default -> throw new IllegalArgumentException("personal setting deletion kind");
        };
        if (!key.otherUserId().isEmpty() || !key.label().equals(expected)) throw new IllegalArgumentException("personal setting deletion key");
        synchronized (turns) {
            turns.forget(key.subject().userId());
            return store.forget(key).thenApply(snapshot -> null);
        }
    }
}
