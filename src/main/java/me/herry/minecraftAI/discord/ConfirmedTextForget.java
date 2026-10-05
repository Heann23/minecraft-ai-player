package me.herry.minecraftAI.discord;

import java.util.Set;

/** Unambiguous first-person, whole private text only. No voice recognition, quotes, or generated text. */
final class ConfirmedTextForget {
    private static final Set<String> ALL = Set.of(
            "내기억모두지워줘", "내기억을모두지워줘", "내기억을전부지워줘",
            "내기억을모두삭제해줘", "내기억을전부삭제해줘",
            "제기억모두지워주세요", "제기억을모두지워주세요", "제기억을전부지워주세요",
            "제기억을모두삭제해주세요", "제기억을전부삭제해주세요",
            "나에대한기억을모두지워줘", "나에대한기억을전부지워줘", "나에대한기억을모두삭제해줘",
            "저에대한기억을모두지워주세요", "저에대한기억을전부지워주세요", "저에대한기억을모두삭제해주세요",
            "내기억과대화문맥을모두지워줘", "제기억과대화문맥을모두지워주세요");
    private ConfirmedTextForget() {}
    static boolean requested(String input) {
        if (input == null || input.indexOf('?') >= 0 || input.indexOf('？') >= 0) return false;
        String text = input.strip().replaceFirst("(?iu)^(?:해리|Herry)(?:님|씨|야|아)?(?:\\s*[,，:]\\s*|\\s+)", "")
                .replaceAll("\\s+", "").replaceAll("[.!。！]+$", "");
        return ALL.contains(text);
    }
}
