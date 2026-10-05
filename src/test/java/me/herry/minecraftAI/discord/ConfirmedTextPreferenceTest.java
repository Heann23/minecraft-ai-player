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
}
