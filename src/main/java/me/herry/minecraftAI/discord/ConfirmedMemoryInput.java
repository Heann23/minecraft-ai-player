package me.herry.minecraftAI.discord;

import java.util.Optional;
import java.util.regex.Pattern;

/** Small explicit Korean forms. Ambiguous STT, quotes, and LLM inventions require confirmation. */
public final class ConfirmedMemoryInput {
    public enum Consent { ALLOWED, REFUSED }
    private static final Pattern NAME = Pattern.compile("^(?:내 이름은|제 이름은) ([가-힣A-Za-z]{1,20}?)(?:이야|야|입니다|예요|이에요)[.!]?$|^([가-힣A-Za-z]{1,20}?)(?:이라고|라고) 불러 ?(?:줘|주세요)[.!]?$" );
    private ConfirmedMemoryInput() {}
    /** reliableFinal must come from final/confirmed STT, never an interim transcript. */
    public static Optional<String> introducedName(String text, boolean reliableFinal) {
        if (!reliableFinal || text == null || text.length() > 100) return Optional.empty();
        var matcher = NAME.matcher(text.strip());
        if (!matcher.matches()) return Optional.empty();
        return Optional.of((matcher.group(1) != null ? matcher.group(1) : matcher.group(2)).strip());
    }
    public static Optional<Consent> consent(String text, boolean reliableFinal) {
        if (!reliableFinal || text == null) return Optional.empty();
        return switch (text.strip().replaceAll("[.!]$", "")) {
            case "응", "네", "좋아", "괜찮아", "말 편하게 해", "반말해도 돼", "반말해도 됩니다" -> Optional.of(Consent.ALLOWED);
            case "아니", "아니요", "존댓말로 해", "반말하지 마", "싫어" -> Optional.of(Consent.REFUSED);
            default -> Optional.empty();
        };
    }
}
