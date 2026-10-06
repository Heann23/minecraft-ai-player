package me.herry.minecraftAI.discord;

import java.util.Set;

/** Whole, direct private text only. Never called with speech recognition, model output, or prior history. */
final class ConfirmedTextPreference {
    record Change(DiscordMemory.Kind kind, String value) {
        boolean jokesAllowed() { return kind == DiscordMemory.Kind.AVOID_JOKE && value.equals("ALLOWED"); }
        String label() { return switch (kind) { case NAME -> "preferred"; case SPEECH_AGREEMENT -> "casual"; case AVOID_JOKE -> "all"; default -> throw new IllegalArgumentException("personal preference kind"); }; }
        String reply(boolean casual) {
            if (kind == DiscordMemory.Kind.NAME) {
                String politeName = value.endsWith("님") || value.endsWith("씨") ? value : value + "님";
                char last = value.charAt(value.length() - 1);
                boolean finalConsonant = last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
                return casual ? value + (finalConsonant ? "이라고" : "라고") + " 부를게."
                        : politeName + (politeName.endsWith("씨") ? "로" : "으로") + " 부를게요.";
            }
            if (kind == DiscordMemory.Kind.SPEECH_AGREEMENT) return value.equals("ALLOWED")
                    ? "알겠어. 나한테 반말을 허락한 걸로 기억할게." : "알겠어요. 앞으로 존댓말로 이야기할게요.";
            return jokesAllowed() ? casual ? "가벼운 장난은 허용하는 걸로 기억할게. 진지한 이야기에서는 줄일게." : "가벼운 장난은 허용하는 걸로 기억할게요. 진지한 이야기에서는 줄일게요."
                    : casual ? "미안해. 장난은 멈추고 담백하게 이야기할게." : "미안해요. 장난은 멈추고 담백하게 이야기할게요.";
        }
    }
    private static final Set<String> AVOID = Set.of("장난하지마", "장난하지마세요", "장난하지말아주세요", "농담하지마", "농담하지마세요",
            "농담하지말아주세요", "장난그만해", "장난그만해요", "장난그만해주세요", "장난은그만해주세요", "농담그만해주세요",
            "놀리지마", "놀리지마세요", "놀리지말아주세요", "장난이불편해요", "농담이불편해요", "장난이싫어요", "농담이싫어요");
    private static final Set<String> ALLOW = Set.of("장난해도돼", "장난해도돼요", "다시장난해도돼요", "가벼운장난은괜찮아요", "농담해도괜찮아요");
    private static final Set<String> CASUAL = Set.of("나한테반말해도돼", "나한테반말해도돼요", "저한테반말해도돼요", "저에게반말해도돼요",
            "나에게반말해도돼요", "반말로말해주세요", "반말로해줘", "말편하게해주세요");
    private static final Set<String> POLITE = Set.of("존댓말써주세요", "존댓말로말해주세요", "존댓말로해주세요", "존댓말로해", "반말하지마", "반말하지마세요", "반말하지말아주세요");
    private ConfirmedTextPreference() {}
    static Change read(String input) {
        String personal = input.strip().replaceFirst("(?iu)^(?:해리|Herry)(?:님|씨|야|아)?(?:\\s*[,，:]\\s*|\\s+)", "");
        var name = ConfirmedTextName.read(personal);
        if (name.isPresent()) return new Change(DiscordMemory.Kind.NAME, name.get());
        boolean question = java.util.regex.Pattern.compile("[?？][.!?。！？]*$").matcher(personal).find();
        String text = input.strip().replaceAll("\\s+", "").replaceFirst("[.!?。！？]+$", "")
                .replaceFirst("(?iu)^(?:해리|Herry)(?:님|씨|야|아)?[,:，]?", "");
        if (POLITE.contains(text)) return new Change(DiscordMemory.Kind.SPEECH_AGREEMENT, "REFUSED");
        if (!question && CASUAL.contains(text)) return new Change(DiscordMemory.Kind.SPEECH_AGREEMENT, "ALLOWED");
        if (AVOID.contains(text)) return new Change(DiscordMemory.Kind.AVOID_JOKE, "AVOID");
        return !question && ALLOW.contains(text) ? new Change(DiscordMemory.Kind.AVOID_JOKE, "ALLOWED") : null;
    }
}
