package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedTextPreferenceTest {
    @Test void directWholeRequestsAllowOnlyMinimalPersonalJokeSetting() {
        for (String text : java.util.List.of("해리님, 장난 그만해 주세요.", "장난이 불편해요", "농담하지 말아 주세요!", "해리야 놀리지 마", "Herry씨, 장난하지 마세요"))
            assertFalse(ConfirmedTextPreference.read(text).jokesAllowed(), text);
        for (String text : java.util.List.of("해리님, 다시 장난해도 돼요.", "가벼운 장난은 괜찮아요", "농담해도 괜찮아요"))
            assertTrue(ConfirmedTextPreference.read(text).jokesAllowed(), text);
    }
    @Test void quotationsDescriptionsOtherPeopleQuestionsAndUnrelatedConsentCannotChangeSetting() {
        for (String text : java.util.List.of("\"장난 그만해 주세요\"는 무슨 뜻이에요?", "‘장난 그만해 주세요’", "친구가 장난이 불편해요라고 말했어요",
                "민수님, 장난 그만해 주세요", "장난 그만해 주세요라고 말하면 되나요?", "반말해도 돼요", "장난 그만해 달라고 부탁한 적이 있어요",
                "장난 그만해 주세요. 다른 사람 이야기예요", "이 문장을 읽어 주세요: 장난 그만해 주세요")) assertNull(ConfirmedTextPreference.read(text), text);
    }
    @Test void permissionQuestionsAndBareYesCannotGrantSpeechOrJokePermission() {
        for (String text : java.util.List.of("장난해도 돼요?", "다시 장난해도 돼요？", "해리님, 저한테 반말해도 돼요?", "저한테 반말해도 돼요?!", "다시 장난해도 돼요?!", "반말해도 돼요",
                "네", "응", "좋아", "괜찮아요", "친구가 반말하지 말라고 했어요", "\"존댓말로 말해 주세요\"라고 했어요"))
            assertNull(ConfirmedTextPreference.read(text), text);
    }
    @Test void explicitNamesAndDirectedSpeechRequestsUseSeparateKindsAndRespectfulConfirmation() {
        var name = ConfirmedTextPreference.read("해리님, 제 이름은 민수예요");
        assertEquals(DiscordMemory.Kind.NAME, name.kind()); assertEquals("민수", name.value()); assertEquals("preferred", name.label());
        assertEquals("민수님으로 부를게요.", name.reply(false));
        var consonant = ConfirmedTextPreference.read("성훈이라고 불러 주세요"); assertEquals("성훈이라고 부를게.", consonant.reply(true));
        var honorific = ConfirmedTextPreference.read("민수님이라고 불러 주세요"); assertEquals("민수님으로 부를게요.", honorific.reply(false));
        var allow = ConfirmedTextPreference.read("해리님, 저한테 반말해도 돼요");
        assertEquals(DiscordMemory.Kind.SPEECH_AGREEMENT, allow.kind()); assertEquals("ALLOWED", allow.value());
        var refuse = ConfirmedTextPreference.read("존댓말로 말해 주세요!");
        assertEquals(DiscordMemory.Kind.SPEECH_AGREEMENT, refuse.kind()); assertEquals("REFUSED", refuse.value());
        assertEquals("알겠어요. 앞으로 존댓말로 이야기할게요.", refuse.reply(true));
    }
}
