package me.herry.minecraftAI.discord;

import java.util.Set;

/** Exact whole-utterance yes or no for a question Herry just asked. Questions, quotes and longer sentences never count. */
final class ConfirmedYesNo {
    private static final Set<String> YES = Set.of("응", "네", "예", "맞아", "맞아요", "맞습니다", "네맞아요", "응맞아", "그래", "그래요", "그렇습니다",
            "응응", "네네", "넵", "좋아", "좋아요");
    private static final Set<String> NO = Set.of("아니", "아니요", "아니오", "아니에요", "아냐", "아니야", "틀려", "틀려요", "틀렸어", "틀렸어요",
            "아니아니", "싫어", "싫어요");
    private ConfirmedYesNo() {}
    /** TRUE for an exact yes, FALSE for an exact no, null for anything else. */
    static Boolean read(String input) {
        if (input == null || input.length() > 40 || input.indexOf('?') >= 0 || input.indexOf('？') >= 0) return null;
        String text = input.strip().replaceAll("\\s+", "").replaceAll("[.!,。！]+$", "");
        return YES.contains(text) ? Boolean.TRUE : NO.contains(text) ? Boolean.FALSE : null;
    }
}
