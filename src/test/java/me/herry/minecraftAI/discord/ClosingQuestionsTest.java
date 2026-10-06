package me.herry.minecraftAI.discord;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClosingQuestionsTest {
    private static ConversationTurns.Line user(String user, long time) { return new ConversationTurns.Line(user, "Herry", "질문", false, time); }
    private static ConversationTurns.Line answer(String user, String text, long time) { return new ConversationTurns.Line("Herry", user, text, true, time); }
    @Test void countsOnlyTrailingQuestionEndingsForThisUser() {
        List<ConversationTurns.Line> context = new ArrayList<>(List.of(user("A", 1), answer("A", "뭐 할까요?", 2), user("A", 3), answer("A", "나무예요.", 4),
                user("A", 5), answer("A", "더 할까요?", 6), user("A", 7)));
        assertEquals(1, ClosingQuestions.recent(context, "A")); assertTrue(ClosingQuestions.allowed(context, "A"));
        context.addAll(List.of(answer("A", "또 갈까요？", 8), user("A", 9)));
        assertEquals(2, ClosingQuestions.recent(context, "A")); assertFalse(ClosingQuestions.allowed(context, "A"));
        context.addAll(List.of(answer("A", "그렇게 해요.", 10), user("A", 11)));
        assertEquals(0, ClosingQuestions.recent(context, "A")); assertTrue(ClosingQuestions.allowed(context, "A"));
    }
    @Test void anotherUsersAnswersAndUserLinesDoNotCount() {
        List<ConversationTurns.Line> context = List.of(answer("B", "뭐 할까요?", 1), answer("B", "더 할까요?", 2), answer("B", "또요?", 3),
                new ConversationTurns.Line("A", "Herry", "정말요?", false, 4));
        assertEquals(0, ClosingQuestions.recent(context, "A")); assertTrue(ClosingQuestions.allowed(context, "A"));
        assertEquals(3, ClosingQuestions.recent(context, "B"));
    }
    @Test void aPartiallyHeardAnswerCountsOnlyIfTheHeardTextEndsWithAQuestion() {
        List<ConversationTurns.Line> context = List.of(answer("A", "나무부터 모아요. 그다음에", 1), answer("A", "철도 구할까요?", 2));
        assertEquals(1, ClosingQuestions.recent(context, "A"));
    }
    @Test void trimmingKeepsTheFirstSentenceAndDropsOnlyClosingQuestions() {
        assertEquals("나무예요. 철은 그다음이에요.", ClosingQuestions.trimmed("나무예요. 철은 그다음이에요. 같이 갈까요?"));
        assertEquals("나무예요.", ClosingQuestions.trimmed("나무예요. 갈까요? 아니면 쉴까요?"));
        assertEquals("나무예요.", ClosingQuestions.trimmed("나무예요. 갈까요? "));
        assertEquals("갈까요?", ClosingQuestions.trimmed("갈까요? 쉴까요?"));
    }
    @Test void loneQuestionsStatementsAndQuotedQuestionsAreUntouched() {
        for (String text : List.of("어떤 도구를 말씀하세요?", "나무부터 모아요.", "나무예요. 철은 그다음이에요.", "그가 \"같이 갈까요?\" 하고 말했어요.",
                "나무예요. \"같이 갈까요?\"", "질문은 중간에? 있고 끝은 평서문이에요."))
            assertEquals(text, ClosingQuestions.trimmed(text), text);
    }
}
