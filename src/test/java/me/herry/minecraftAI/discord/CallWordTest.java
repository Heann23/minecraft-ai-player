package me.herry.minecraftAI.discord;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CallWordTest {
    @Test void aCallIsTheNameFirstThenASpacePunctuationOrTheEnd() {
        for (String text : List.of("해리야 뭐해", "해리야. 지금 뭐 해?", "해리야, 안녕", "해리님! 계세요?", "해리 씨", "해리", "해리야", " 해리아 뭐해 ", "Herry, hi", "herry. 뭐해",
                "해리야… 있잖아", "해리야。안녕", "해리?", "해리야~ 놀자"))
            assertTrue(CallWord.called(text), text);
        for (String text : List.of("해리포터 봤어?", "해리가 어제 죽었대", "야 해리 뭐해", "방해리 안녕", "Herry23 어디감?", "\"해리야\"라고 했어", "뭐해 해리야", "", "  "))
            assertFalse(CallWord.called(text), text);
        assertFalse(CallWord.called(null));
    }
    @Test void theBodyIsTheSentenceWithoutItsLeadingCall() {
        assertEquals("지금 뭐 해?", CallWord.body("해리야. 지금 뭐 해?"));
        assertEquals("안녕", CallWord.body(" 해리님,  안녕 "));
        assertEquals("", CallWord.body("해리야."));
        assertEquals("해리포터 봤어?", CallWord.body("해리포터 봤어?"));
        assertEquals("그냥 하는 말", CallWord.body("  그냥 하는 말 "));
    }
    @Test void everyReaderAcceptsTheCallWrittenAsItsOwnSentence() {
        var members = Set.of("A", "B");
        assertEquals(ConversationTurns.Address.CHARACTER, AddresseeResolver.resolve("해리야. 지금 뭐 해?", "A", members, Map.of()).address());
        assertEquals(ConversationTurns.Address.CHARACTER, AddresseeResolver.resolve("해리아 뭐해", "A", members, Map.of()).address());
        assertEquals("B", AddresseeResolver.resolve("민수야. 이리 와", "A", members, Map.of("민수", "B")).otherUserId());
        assertEquals(ConversationTurns.Address.UNKNOWN, AddresseeResolver.resolve("민수가 이리 오래", "A", members, Map.of("민수", "B")).address());
        assertTrue(ConversationStop.requested("해리야. 그만."));
        assertFalse(ConversationStop.requested("해리야. 그만 좀 캐자"));
        assertEquals(DiscordMemory.Kind.AVOID_JOKE, ConfirmedTextPreference.read("해리야. 장난하지 마.").kind());
        assertEquals(ConfirmedTextForget.Target.NAME, ConfirmedTextForget.read("해리야. 내 이름만 잊어 줘."));
        assertEquals(VoiceConfirmation.CASUAL_SPEECH, VoiceConfirmation.request("해리야. 반말해도 돼."));
    }
}
