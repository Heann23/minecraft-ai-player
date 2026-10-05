package me.herry.minecraftAI.discord;

import java.util.Set;

/** Whole, direct private text only. Never called with speech recognition, model output, or prior history. */
final class ConfirmedTextPreference {
    record Change(boolean jokesAllowed) {
        String value() { return jokesAllowed ? "ALLOWED" : "AVOID"; }
        String reply(boolean casual) {
            return jokesAllowed ? casual ? "가벼운 장난은 허용하는 걸로 기억할게. 진지한 이야기에서는 줄일게." : "가벼운 장난은 허용하는 걸로 기억할게요. 진지한 이야기에서는 줄일게요."
                    : casual ? "미안해. 장난은 멈추고 담백하게 이야기할게." : "미안해요. 장난은 멈추고 담백하게 이야기할게요.";
        }
    }
    private static final Set<String> AVOID = Set.of("장난하지마", "장난하지마세요", "장난하지말아주세요", "농담하지마", "농담하지마세요",
            "농담하지말아주세요", "장난그만해", "장난그만해요", "장난그만해주세요", "장난은그만해주세요", "농담그만해주세요",
            "놀리지마", "놀리지마세요", "놀리지말아주세요", "장난이불편해요", "농담이불편해요", "장난이싫어요", "농담이싫어요");
    private static final Set<String> ALLOW = Set.of("장난해도돼", "장난해도돼요", "다시장난해도돼요", "가벼운장난은괜찮아요", "농담해도괜찮아요");
    private ConfirmedTextPreference() {}
    static Change read(String input) {
        String text = input.strip().replaceAll("\\s+", "").replaceFirst("[.!?。！？]+$", "")
                .replaceFirst("(?iu)^(?:해리|Herry)(?:님|씨|야|아)?[,，]?", "");
        if (AVOID.contains(text)) return new Change(false);
        return ALLOW.contains(text) ? new Change(true) : null;
    }
}
