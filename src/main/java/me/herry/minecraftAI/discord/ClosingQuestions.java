package me.herry.minecraftAI.discord;

import java.util.List;

/** Keeps Herry from ending every answer with a question: after two question endings in a row the next one is trimmed. */
final class ClosingQuestions {
    static final int LIMIT = 2;
    private ClosingQuestions() {}
    static boolean endsWithQuestion(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.endsWith("?") || stripped.endsWith("？");
    }
    /** Most recent answers to this user that ended with a question without a plain answer in between. Only text that was heard counts. */
    static int recent(List<ConversationTurns.Line> context, String userId) {
        int count = 0;
        for (int index = context.size() - 1; index >= 0; index--) {
            var line = context.get(index);
            if (!line.assistant() || !line.target().equals(userId)) continue;
            if (!endsWithQuestion(line.text())) break;
            count++;
        }
        return count;
    }
    static boolean allowed(List<ConversationTurns.Line> context, String userId) { return recent(context, userId) < LIMIT; }
    /** Drops the closing question sentences of a multi-sentence answer. The first sentence is always kept, so a lone question stays. */
    static String trimmed(String answer) {
        var sentences = SentenceChunks.split(answer);
        int end = sentences.size();
        while (end > 1 && (sentences.get(end - 1).isBlank() || endsWithQuestion(sentences.get(end - 1)))) end--;
        if (end == sentences.size()) return answer;
        String kept = String.join("", sentences.subList(0, end)).strip();
        return kept.codePoints().anyMatch(Character::isLetterOrDigit) ? kept : answer;
    }
}
