package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationStopTest {
    @Test void finalTranscriptionPunctuationDoesNotTurnStopIntoANewResponse() {
        for (String text : new String[]{"그만", "그만.", "잠깐!", "멈춰?", "잠시만。", "그만해！", " 해리야, 그만! ", "Herry 잠깐."})
            assertTrue(ConversationStop.requested(text), text);
    }
    @Test void QuotedReportedAndPartialCommandsAreNotStops() {
        for (String text : new String[]{"그만이라는 표현 알려줘", "그만해도 되나요?", "그만 철 좀 캐자", "\"그만\"", "민수야 그만", "해리 그만이라고 말했어", ""})
            assertFalse(ConversationStop.requested(text), text);
        assertFalse(ConversationStop.requested(null));
    }
    @Test void explicitStopNeedsNoPriorEngagementButPlainStopDoes() {
        var turns = new ConversationTurns(() -> 1000, 60_000, 8); turns.join("A");
        assertEquals(ConversationTurns.Decision.IGNORE, turns.accept("A", "1", "그만.",
                ConversationTurns.Address.UNKNOWN, ConversationStop.requested("그만.")).decision());
        String text = "해리야 그만!";
        var target = AddresseeResolver.resolve(text, "A", java.util.Set.of("A"), java.util.Map.of());
        assertEquals(ConversationTurns.Decision.STOPPED, turns.accept("A", "2", text, target.address(), ConversationStop.requested(text)).decision());
        assertTrue(turns.context().isEmpty()); turns.close();
    }
}
