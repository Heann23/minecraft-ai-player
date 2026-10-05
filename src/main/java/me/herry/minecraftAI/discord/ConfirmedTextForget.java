package me.herry.minecraftAI.discord;

import java.util.Set;

/** Unambiguous first-person, whole private text only. No voice recognition, quotes, or generated text. */
final class ConfirmedTextForget {
    enum Target {
        ALL, NAME, SPEECH, JOKE;
        DiscordMemory.Key key(DiscordMemory.Subject subject) {
            return switch (this) {
                case NAME -> new DiscordMemory.Key(subject, DiscordMemory.Kind.NAME, "", "preferred");
                case SPEECH -> new DiscordMemory.Key(subject, DiscordMemory.Kind.SPEECH_AGREEMENT, "", "casual");
                case JOKE -> new DiscordMemory.Key(subject, DiscordMemory.Kind.AVOID_JOKE, "", "all");
                case ALL -> throw new IllegalStateException("whole memory deletion has no single key");
            };
        }
        String reply(boolean casual) {
            return switch (this) {
                case ALL -> "본인의 저장된 기억과 이전 대화 문맥을 모두 지웠어요. 기본 존댓말로 다시 이야기할게요.";
                case NAME -> casual ? "저장한 네 호칭과 이전 대화 문맥을 지웠어. 말투와 장난 설정은 유지했어."
                        : "본인의 저장된 호칭과 이전 대화 문맥을 지웠어요. 말투와 장난 설정은 유지했어요.";
                case SPEECH -> "본인의 말투 합의와 이전 대화 문맥을 지웠어요. 기본 존댓말로 이야기할게요.";
                case JOKE -> casual ? "저장한 네 장난 설정과 이전 대화 문맥을 지웠어." : "본인의 장난 설정과 이전 대화 문맥을 지웠어요.";
            };
        }
    }
    private static final Set<String> ALL = Set.of(
            "내기억모두지워줘", "내기억을모두지워줘", "내기억을전부지워줘",
            "내기억을모두삭제해줘", "내기억을전부삭제해줘",
            "제기억모두지워주세요", "제기억을모두지워주세요", "제기억을전부지워주세요",
            "제기억을모두삭제해주세요", "제기억을전부삭제해주세요",
            "나에대한기억을모두지워줘", "나에대한기억을전부지워줘", "나에대한기억을모두삭제해줘",
            "저에대한기억을모두지워주세요", "저에대한기억을전부지워주세요", "저에대한기억을모두삭제해주세요",
            "내기억과대화문맥을모두지워줘", "제기억과대화문맥을모두지워주세요");
    private static final Set<String> NAME = Set.of("내이름만잊어줘", "제이름만잊어주세요", "내이름만지워줘", "제이름만지워주세요",
            "내호칭만지워줘", "제호칭만지워주세요", "내이름기억만지워줘", "제이름기억만삭제해주세요");
    private static final Set<String> SPEECH = Set.of("내말투설정만지워줘", "제말투설정만지워주세요", "내말투설정만삭제해줘",
            "제말투설정만삭제해주세요", "내반말허락만지워줘", "제가반말허락한기억만지워주세요");
    private static final Set<String> JOKE = Set.of("내장난설정만지워줘", "제장난설정만지워주세요", "내농담설정만지워줘",
            "제농담설정만지워주세요", "제장난설정만삭제해주세요", "제농담설정만삭제해주세요");
    private ConfirmedTextForget() {}
    static boolean requested(String input) {
        return read(input) != null;
    }
    static Target read(String input) {
        if (input == null || input.indexOf('?') >= 0 || input.indexOf('？') >= 0) return null;
        String text = input.strip().replaceFirst("(?iu)^(?:해리|Herry)(?:님|씨|야|아)?(?:\\s*[,，:]\\s*|\\s+)", "")
                .replaceAll("\\s+", "").replaceAll("[.!。！]+$", "");
        return ALL.contains(text) ? Target.ALL : NAME.contains(text) ? Target.NAME
                : SPEECH.contains(text) ? Target.SPEECH : JOKE.contains(text) ? Target.JOKE : null;
    }
}
