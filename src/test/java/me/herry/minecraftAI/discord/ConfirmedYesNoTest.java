package me.herry.minecraftAI.discord;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedYesNoTest {
    @Test void onlyExactWholeUtteranceYesAndNoCount() {
        for (String yes : List.of("응", "네.", " 네 맞아요 ", "맞습니다!", "그래요,")) assertEquals(Boolean.TRUE, ConfirmedYesNo.read(yes), yes);
        for (String no : List.of("아니", "아니요.", "아니에요", "틀렸어요")) assertEquals(Boolean.FALSE, ConfirmedYesNo.read(no), no);
        for (String other : List.of("네?", "응？", "응 근데 아니야", "네 좋아요", "음", "", "\"네\"", "맞아요 그리고", "네 그런데 다른 이름이에요"))
            assertNull(ConfirmedYesNo.read(other), other);
        assertNull(ConfirmedYesNo.read(null));
    }
    @Test void echoedNameQuestionUsesAPoliteCallingForm() {
        assertEquals("민수님이라고 부르면 될까요?", VoiceNameConfirmation.question("민수"));
        assertEquals("민수님이라고 부르면 될까요?", VoiceNameConfirmation.question("민수님"));
        assertEquals("민수씨라고 부르면 될까요?", VoiceNameConfirmation.question("민수씨"));
        assertTrue(VoiceNameConfirmation.question("Minsu").length() <= 240);
    }
}
