package me.herry.minecraftAI.discord;

import java.util.Map;
import java.util.Objects;

/** Only confirmed, minimal facts belong here. Audio and transcripts have no storage field. */
public final class DiscordMemory {
    private DiscordMemory() {}
    public enum Kind { NAME, SPEECH_AGREEMENT, AVOID_JOKE, RELATION, ADDRESS_STYLE, ITEM_STORY }
    public enum Evidence { EXPLICIT, OBSERVED }
    public record Subject(String guildId, String characterId, String userId) {
        public Subject {
            identifier(guildId); identifier(characterId); identifier(userId);
        }
    }
    public record Key(Subject subject, Kind kind, String otherUserId, String label) {
        public Key {
            Objects.requireNonNull(subject); Objects.requireNonNull(kind);
            Objects.requireNonNull(otherUserId);
            if (kind == Kind.RELATION || kind == Kind.ADDRESS_STYLE) {
                identifier(otherUserId);
                if (otherUserId.equals(subject.userId)) throw new IllegalArgumentException("self relationship");
            } else if (!otherUserId.isEmpty()) throw new IllegalArgumentException("unexpected relationship target");
            identifier(label);
        }
        public boolean involves(Subject person) {
            return subject.guildId.equals(person.guildId) && subject.characterId.equals(person.characterId)
                    && (subject.userId.equals(person.userId) || otherUserId.equals(person.userId));
        }
    }
    public record Fact(Key key, String value, Evidence evidence, String sourceId,
                       long recordedAt, long expiresAt, long revision) {
        public Fact {
            Objects.requireNonNull(key); Objects.requireNonNull(evidence);
            if (value == null || value.isBlank() || value.length() > 500) throw new IllegalArgumentException("memory value");
            identifier(sourceId);
            if (recordedAt < 0 || revision < 1 || (expiresAt != 0 && expiresAt <= recordedAt)) throw new IllegalArgumentException("memory metadata");
            if (evidence == Evidence.OBSERVED && (key.kind != Kind.ADDRESS_STYLE || expiresAt == 0))
                throw new IllegalArgumentException("only expiring address habits may be inferred");
        }
        public boolean expired(long now) { return expiresAt != 0 && now >= expiresAt; }
    }
    public record Snapshot(long revision, long createdAt, Map<Key, Fact> facts, Map<Subject, Long> deleted,
                           Map<Key, Long> erasedKeys) {
        public Snapshot {
            if (revision < 0 || createdAt < 0) throw new IllegalArgumentException("snapshot metadata");
            facts = Map.copyOf(facts); deleted = Map.copyOf(deleted); erasedKeys = Map.copyOf(erasedKeys);
            if (facts.size() > 10_000 || deleted.size() > 10_000 || erasedKeys.size() > 10_000) throw new IllegalArgumentException("memory capacity");
            for (Map.Entry<Key, Fact> entry : facts.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().key) || entry.getValue().revision > revision)
                    throw new IllegalArgumentException("fact revision/key");
            }
            for (long floor : deleted.values()) if (floor < 1 || floor > revision) throw new IllegalArgumentException("deletion revision");
            for (long floor : erasedKeys.values()) if (floor < 1 || floor > revision) throw new IllegalArgumentException("key deletion revision");
        }
        public static Snapshot empty(long now) { return new Snapshot(0, now, Map.of(), Map.of(), Map.of()); }
        public boolean erased(Fact fact) {
            return fact.revision <= erasedKeys.getOrDefault(fact.key, 0L)
                    || deleted.entrySet().stream().anyMatch(e -> fact.key.involves(e.getKey()) && fact.revision <= e.getValue());
        }
    }
    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 100 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("memory identifier");
    }
}
