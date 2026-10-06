package me.herry.minecraftAI.discord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures, never changes, whether a new answer repeats one the same user recently heard. Only the yes/no result leaves this class,
 * so a count can be shown without any conversation text. Short acknowledgements are legitimately repeated and are not compared.
 */
final class AnswerRepetition {
    static final int RECENT = 3, MINIMUM_LETTERS = 8;
    static final double SIMILAR = 0.9;
    private AnswerRepetition() {}
    static boolean repeated(String answer, List<ConversationTurns.Line> context, String userId) {
        String current = letters(answer);
        if (current.length() < MINIMUM_LETTERS) return false;
        int compared = 0;
        for (int index = context.size() - 1; index >= 0 && compared < RECENT; index--) {
            var line = context.get(index);
            if (!line.assistant() || !line.target().equals(userId)) continue;
            compared++;
            if (similar(current, letters(line.text()))) return true;
        }
        return false;
    }
    private static String letters(String text) {
        StringBuilder result = new StringBuilder();
        if (text != null) text.codePoints().filter(Character::isLetterOrDigit).map(Character::toLowerCase).forEach(result::appendCodePoint);
        return result.toString();
    }
    /** Dice coefficient over character pairs. Equal text is similar; very short text is not compared. */
    private static boolean similar(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() < 2 || b.length() < 2) return false;
        Map<String, Integer> pairs = new HashMap<>();
        for (int i = 0; i + 2 <= a.length(); i++) pairs.merge(a.substring(i, i + 2), 1, Integer::sum);
        int shared = 0;
        for (int i = 0; i + 2 <= b.length(); i++) {
            String pair = b.substring(i, i + 2); Integer left = pairs.get(pair);
            if (left != null && left > 0) { shared++; pairs.put(pair, left - 1); }
        }
        return 2.0 * shared / ((a.length() - 1) + (b.length() - 1)) >= SIMILAR;
    }
}
