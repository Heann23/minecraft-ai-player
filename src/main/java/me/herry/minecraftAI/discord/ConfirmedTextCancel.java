package me.herry.minecraftAI.discord;

import java.util.regex.Pattern;

/**
 * Explicit requests to stop the pending answer, taken from a whole private text input only. Never voice, quotes or generated text.
 * A bare "그만"/"멈춰" is not accepted: it may mean a game action or a joke and the text path cannot tell which.
 */
final class ConfirmedTextCancel {
    private static final Pattern REQUEST = Pattern.compile(
            "(?:(?:지금|일단)?(?:진행중인|하던|준비중인|텍스트)?(?:답변|대답|응답)(?:준비)?|(?:지금|방금)?(?:한)?(?:내|제)?질문)"
                    + "(?:을|를|은|는)?(?:(?:중단|취소|그만)(?:해줘|해주세요|해요|해)|멈춰(?:줘|주세요|요)?)");
    private ConfirmedTextCancel() {}
    static boolean requested(String input) {
        if (input == null || input.indexOf('?') >= 0 || input.indexOf('？') >= 0) return false;
        String text = input.strip().replaceFirst("(?iu)^(?:해리|Herry)(?:님|씨|야|아)?(?:\\s*[,，:]\\s*|\\s+)", "")
                .replaceAll("\\s+", "").replaceAll("[.!。！]+$", "");
        return REQUEST.matcher(text).matches();
    }
    /** Acceptance only: a message whose Discord delivery already started cannot be recalled. */
    static String reply(boolean pending) {
        return pending ? "진행 중이던 답변의 중단을 요청했어요. 이전 대화와 저장된 기억은 유지해요."
                : "지금 중단할 답변은 없어요. 이전 대화와 저장된 기억은 유지해요.";
    }
}
