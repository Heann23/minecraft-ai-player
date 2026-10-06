package me.herry.minecraftAI.discord;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnswerRepetitionTest {
    private static ConversationTurns.Line heard(String user, String text) { return new ConversationTurns.Line("Herry", user, text, true, 1); }
    private static ConversationTurns.Line said(String user, String text) { return new ConversationTurns.Line(user, "Herry", text, false, 1); }
    @Test void sameOrNearlySameAnswerIsRepeatedIgnoringPunctuationSpacingAndCase() {
        var context = List.of(said("A", "뭐 해요?"), heard("A", "오늘은 나무를 모으러 갈게요."));
        assertTrue(AnswerRepetition.repeated("오늘은 나무를 모으러 갈게요!", context, "A"));
        assertTrue(AnswerRepetition.repeated("오늘은  나무를  모으러  갈게요", context, "A"));
        assertTrue(AnswerRepetition.repeated("오늘은 나무를 모으러 갈게요요", context, "A"));
    }
    @Test void aDifferentAnswerIsNotRepeated() {
        var context = List.of(heard("A", "오늘은 나무를 모으러 갈게요."));
        assertFalse(AnswerRepetition.repeated("철은 곡괭이를 만든 다음에 구울게요.", context, "A"));
        assertFalse(AnswerRepetition.repeated("오늘은 나무를 모으러 갈게요. 그리고 철도 찾아볼게요.", context, "A"));
    }
    @Test void shortAcknowledgementsAreNeverCompared() {
        var context = List.of(heard("A", "네 알겠어요."), heard("A", "좋아요!"));
        assertFalse(AnswerRepetition.repeated("네 알겠어요.", context, "A")); assertFalse(AnswerRepetition.repeated("좋아요!", context, "A"));
    }
    @Test void onlyTheLastThreeAnswersToTheSameUserCount() {
        var context = List.of(heard("A", "오늘은 나무를 모으러 갈게요."), heard("A", "첫 번째 다른 대답이에요 정말로."), heard("B", "오늘은 나무를 모으러 갈게요."),
                heard("A", "두 번째 다른 대답이에요 정말로."), said("A", "오늘은 나무를 모으러 갈게요."), heard("A", "세 번째 다른 대답이에요 정말로."));
        assertTrue(AnswerRepetition.repeated("첫 번째 다른 대답이에요 정말로.", context, "A"));
        assertFalse(AnswerRepetition.repeated("오늘은 나무를 모으러 갈게요.", context, "A"));
        assertTrue(AnswerRepetition.repeated("오늘은 나무를 모으러 갈게요.", context, "B"));
    }
    @Test void aUsersOwnWordsAndMissingTextAreNotAnAnswer() {
        assertFalse(AnswerRepetition.repeated("오늘은 나무를 모으러 갈게요.", List.of(said("A", "오늘은 나무를 모으러 갈게요.")), "A"));
        assertFalse(AnswerRepetition.repeated(null, List.of(heard("A", "오늘은 나무를 모으러 갈게요.")), "A"));
        assertFalse(AnswerRepetition.repeated("오늘은 나무를 모으러 갈게요.", List.of(), "A"));
    }
}
