package me.herry.minecraftAI.discord;

import java.util.Set;

/** Explicit temporary-context requests from a whole private text input, never voice or generated text. */
final class ConfirmedTextReset {
    private static final Set<String> REQUESTS = Set.of(
            "이전대화문맥만지워줘", "이전대화문맥만지워주세요",
            "내대화문맥만지워줘", "제대화문맥만지워주세요",
            "임시대화문맥만지워줘", "임시대화문맥만지워주세요",
            "대화문맥만초기화해줘", "대화문맥만초기화해주세요",
            "새대화를시작해줘", "새대화를시작해주세요");
    private ConfirmedTextReset() {}
    static boolean requested(String input) {
        if (input == null || input.indexOf('?') >= 0 || input.indexOf('？') >= 0) return false;
        String text = input.strip().replaceFirst("(?iu)^(?:해리|Herry)(?:님|씨|야|아)?(?:\\s*[,，:]\\s*|\\s+)", "")
                .replaceAll("\\s+", "").replaceAll("[.!。！]+$", "");
        return REQUESTS.contains(text);
    }
    static String reply() {
        return "이전 임시 대화 문맥을 비웠어요. 저장된 호칭·말투·장난 설정은 유지하고 새 대화를 시작해요.";
    }
}
