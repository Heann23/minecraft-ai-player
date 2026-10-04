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

    /** Clear live model/playback routes and shared context before erasing durable memories. */
    public CompletableFuture<Void> forget(Subject person) {
        synchronized (turns) {
            turns.forget(person.userId());
            return store.forget(person).thenApply(snapshot -> null);
        }
    }
}
