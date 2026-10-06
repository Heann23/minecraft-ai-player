package me.herry.minecraftAI.discord;

import java.util.Set;

/**
 * Spoken requests that loosen or delete a personal setting. Speech recognition may mishear, so Herry repeats the
 * request back and applies it only after the same person answers yes on their very next turn. Every sentence is code-owned.
 */
final class VoiceConfirmation {
    record Proposal(Kind kind, String value) {
        enum Kind { NAME, CASUAL_SPEECH, JOKES, FORGET }
        Proposal {
            java.util.Objects.requireNonNull(kind);
            if (value == null || value.isBlank() || value.length() > 20) throw new IllegalArgumentException("confirmation proposal");
            if (kind == Kind.FORGET) ConfirmedTextForget.Target.valueOf(value);
        }
        static Proposal name(String name) { return new Proposal(Kind.NAME, name); }
        static Proposal forget(ConfirmedTextForget.Target target) { return new Proposal(Kind.FORGET, target.name()); }
        ConfirmedTextForget.Target target() { return ConfirmedTextForget.Target.valueOf(value); }
    }
    static final Proposal CASUAL_SPEECH = new Proposal(Proposal.Kind.CASUAL_SPEECH, "ALLOWED");
    static final Proposal JOKES = new Proposal(Proposal.Kind.JOKES, "ALLOWED");

    // Spoken forms are wider than the typed ones because nothing is stored before the echoed question is answered.
    private static final Set<String> CASUAL = Set.of("반말해도돼", "반말해도돼요", "반말해도됩니다", "반말해도괜찮아", "반말해도괜찮아요",
            "반말써도돼", "반말써도돼요", "반말해", "반말하세요", "반말로해", "반말로해요", "반말로해도돼", "반말로해도돼요",
            "반말로말해", "반말로말해도돼", "반말로말해도돼요", "나한테반말해", "나한테반말써도돼", "저한테반말하세요",
            "말편하게해", "말편하게해요", "말편하게하세요", "말편하게해도돼", "말편하게해도돼요", "말편하게해줘",
            "나한테말편하게해", "나한테말편하게해도돼", "저한테말편하게하세요",
            "편하게말해", "편하게말해요", "편하게말하세요", "편하게말해도돼", "편하게말해도돼요", "편하게말해줘", "편하게말해주세요",
            "나한테편하게말해", "나한테편하게말해도돼", "저한테편하게말하세요",
            "말놓아도돼", "말놓아도돼요", "말놔도돼", "말놔도돼요", "말놓으세요", "말놓아", "말놔");
    private static final Set<String> JOKE = Set.of("장난쳐도돼", "장난쳐도돼요", "장난쳐도괜찮아", "장난쳐도괜찮아요",
            "장난해도괜찮아", "장난해도괜찮아요", "농담해도돼", "농담해도돼요", "농담해도괜찮아",
            "다시장난해도돼", "다시장난쳐도돼", "다시장난쳐도돼요", "다시농담해도돼", "다시농담해도돼요",
            "이제장난해도돼", "이제장난해도돼요", "이제장난쳐도돼", "이제장난쳐도돼요", "이제농담해도돼", "이제농담해도돼요",
            "장난다시해도돼", "장난다시해도돼요");
    private static final Set<String> FORGET_ALL = Set.of("내기억지워줘", "내기억다지워줘", "내기억전부지워줘", "내기억싹지워줘",
            "제기억지워주세요", "제기억다지워주세요", "제기억전부지워주세요",
            "나에대한기억지워줘", "나에대한기억다지워줘", "저에대한기억지워주세요", "저에대한기억다지워주세요",
            "내정보지워줘", "내정보다지워줘", "제정보지워주세요", "제정보다지워주세요");
    private static final Set<String> FORGET_NAME = Set.of("내이름잊어줘", "제이름잊어주세요", "내이름지워줘", "제이름지워주세요", "내호칭지워줘", "제호칭지워주세요");
    private static final Set<String> FORGET_SPEECH = Set.of("내말투설정지워줘", "제말투설정지워주세요");
    private static final Set<String> FORGET_JOKE = Set.of("내장난설정지워줘", "제장난설정지워주세요", "내농담설정지워줘", "제농담설정지워주세요");
    private VoiceConfirmation() {}

    /**
     * The whole utterance must be the request. Questions, quotes and longer sentences stay ordinary conversation.
     * Names and requests that only make Herry plainer are handled elsewhere. Null if it is not a request.
     */
    static Proposal request(String input) {
        if (input == null || input.length() > 100 || input.indexOf('?') >= 0 || input.indexOf('？') >= 0) return null;
        String text = input.strip().replaceFirst("(?iu)^(?:해리|Herry)(?:님|씨|야|아)?[\\s,，:.!~]*", "")
                .replaceAll("\\s+", "").replaceAll("[.!。！~]+$", "");
        var target = ConfirmedTextForget.read(text);
        if (target == null) target = FORGET_ALL.contains(text) ? ConfirmedTextForget.Target.ALL : FORGET_NAME.contains(text) ? ConfirmedTextForget.Target.NAME
                : FORGET_SPEECH.contains(text) ? ConfirmedTextForget.Target.SPEECH : FORGET_JOKE.contains(text) ? ConfirmedTextForget.Target.JOKE : null;
        if (target != null) return Proposal.forget(target);
        var typed = ConfirmedTextPreference.read(text);
        if (typed != null && typed.kind() == DiscordMemory.Kind.SPEECH_AGREEMENT && typed.value().equals("ALLOWED")) return CASUAL_SPEECH;
        if (typed != null && typed.jokesAllowed()) return JOKES;
        if (typed != null) return null;
        return CASUAL.contains(text) ? CASUAL_SPEECH : JOKE.contains(text) ? JOKES : null;
    }

    /** One sentence, so the question counts as heard only when all of it was played. */
    static String question(Proposal proposal, boolean casual) {
        return switch (proposal.kind()) {
            case NAME -> casual ? proposal.value() + (finalConsonant(proposal.value()) ? "이라고" : "라고") + " 부르면 될까?"
                    : VoiceNameConfirmation.question(proposal.value());
            case CASUAL_SPEECH -> "제가 반말로 편하게 말해도 된다는 말씀이죠?";
            case JOKES -> casual ? "가벼운 장난을 쳐도 된다는 거지?" : "가벼운 장난을 해도 된다는 말씀이죠?";
            case FORGET -> switch (proposal.target()) {
                case ALL -> casual ? "내가 기억하는 네 이름, 말투, 장난 설정을 모두 지울까?" : "제가 기억하는 이름, 말투, 장난 설정을 모두 지울까요?";
                case NAME -> casual ? "저장한 네 이름만 지울까?" : "저장한 이름만 지울까요?";
                case SPEECH -> casual ? "저장한 말투 설정만 지울까?" : "저장한 말투 설정만 지울까요?";
                case JOKE -> casual ? "저장한 장난 설정만 지울까?" : "저장한 장난 설정만 지울까요?";
            };
        };
    }
    static String declined(Proposal proposal, boolean casual) {
        if (proposal.kind() == Proposal.Kind.NAME) return casual ? "알겠어. 이름을 다시 말해 주면 확인할게." : VoiceNameConfirmation.declined();
        return casual ? "알겠어. 그대로 둘게." : "알겠어요. 그대로 둘게요.";
    }
    /** Asking again for something already in effect needs no question. */
    static String alreadyCasual() { return "이미 편하게 말하고 있어."; }
    /** The setting a confirmed proposal stores; a deletion stores nothing and has none. */
    static ConfirmedTextPreference.Change change(Proposal proposal) {
        return switch (proposal.kind()) {
            case NAME -> new ConfirmedTextPreference.Change(DiscordMemory.Kind.NAME, proposal.value());
            case CASUAL_SPEECH -> new ConfirmedTextPreference.Change(DiscordMemory.Kind.SPEECH_AGREEMENT, "ALLOWED");
            case JOKES -> new ConfirmedTextPreference.Change(DiscordMemory.Kind.AVOID_JOKE, "ALLOWED");
            case FORGET -> null;
        };
    }
    private static boolean finalConsonant(String word) {
        char last = word.charAt(word.length() - 1);
        return last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
    }
}
