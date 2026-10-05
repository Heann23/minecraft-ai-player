package me.herry.minecraftAI.discord;

import java.util.Comparator;
import java.util.List;
import static me.herry.minecraftAI.discord.DiscordMemory.*;

/** Code-owned view of only the invoking user's confirmed preferences. No model or private transcript. */
final class DiscordPersonalSettings {
    private DiscordPersonalSettings() {}
    static String describe(Subject subject, Snapshot snapshot, long now) {
        List<Fact> facts = snapshot.facts().values().stream().filter(fact -> fact.key().subject().equals(subject)
                && fact.key().otherUserId().isEmpty() && fact.evidence() == Evidence.EXPLICIT
                && !fact.expired(now) && !snapshot.erased(fact)).toList();
        String name = value(facts, Kind.NAME, "preferred");
        String speech = value(facts, Kind.SPEECH_AGREEMENT, "casual");
        String jokes = value(facts, Kind.AVOID_JOKE, "all");
        String displayName = name.isEmpty() ? "저장한 호칭 없음" : name.matches("[가-힣A-Za-z]{1,20}") ? name : "저장한 호칭 확인 필요";
        String displaySpeech = switch (speech) {
            case "ALLOWED" -> "반말 허락";
            case "REFUSED" -> "반말 거절 · 존댓말";
            case "" -> "설정 없음 · 기본 존댓말";
            default -> "설정 확인 필요 · 존댓말";
        };
        String displayJokes = switch (jokes) {
            case "ALLOWED" -> "가벼운 장난 허용";
            case "AVOID" -> "장난 중단 · 담백한 대화";
            case "" -> "설정 없음 · 가벼운 장난, 불편하면 중단";
            default -> "설정 확인 필요";
        };
        return "내 설정\n호칭: " + displayName + "\n말투: " + displaySpeech + "\n장난: " + displayJokes
                + "\n변경: /herry name · /herry speech · /herry joke\n전체 삭제: /herry forget";
    }
    private static String value(List<Fact> facts, Kind kind, String label) {
        return facts.stream().filter(fact -> fact.key().kind() == kind && fact.key().label().equals(label))
                .max(Comparator.comparingLong(Fact::revision)).map(Fact::value).orElse("");
    }
}
