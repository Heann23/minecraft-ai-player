package me.herry.minecraftAI.discord;

import java.util.List;
import org.junit.jupiter.api.Test;
import static me.herry.minecraftAI.discord.VoiceConfirmation.Proposal;
import static org.junit.jupiter.api.Assertions.*;

class VoiceConfirmationTest {
    @Test void wholeSpokenRequestsToLoosenBecomeProposalsWithOrWithoutTheCallWord() {
        for (String said : List.of("반말해도 돼", "해리야 반말해도 돼요.", "해리야. 말 편하게 해", "해리님, 저한테 반말해도 돼요", "편하게 말해도 돼", "말 놔도 돼요!", "Herry 반말로 해줘"))
            assertEquals(VoiceConfirmation.CASUAL_SPEECH, VoiceConfirmation.request(said), said);
        for (String said : List.of("장난쳐도 돼", "해리야 다시 장난해도 돼요", "이제 농담해도 돼요.", "해리야, 장난해도 돼"))
            assertEquals(VoiceConfirmation.JOKES, VoiceConfirmation.request(said), said);
    }
    @Test void wholeSpokenRequestsToForgetNameTheirScope() {
        for (String said : List.of("내 기억 지워 줘", "해리야 내 기억 모두 지워 줘.", "제 기억 다 지워 주세요", "나에 대한 기억 지워 줘"))
            assertEquals(Proposal.forget(ConfirmedTextForget.Target.ALL), VoiceConfirmation.request(said), said);
        assertEquals(Proposal.forget(ConfirmedTextForget.Target.NAME), VoiceConfirmation.request("해리야 내 이름만 잊어 줘"));
        assertEquals(Proposal.forget(ConfirmedTextForget.Target.NAME), VoiceConfirmation.request("내 이름 지워 줘"));
        assertEquals(Proposal.forget(ConfirmedTextForget.Target.SPEECH), VoiceConfirmation.request("내 말투 설정만 지워 줘"));
        assertEquals(Proposal.forget(ConfirmedTextForget.Target.JOKE), VoiceConfirmation.request("제 장난 설정 지워 주세요"));
    }
    @Test void questionsQuotesLongerSentencesNamesAndTighteningAreNotProposals() {
        for (String said : List.of("반말해도 돼?", "해리야 장난쳐도 돼요？", "친구가 반말해도 돼 라고 했어", "반말해도 돼 근데 가끔만", "응 반말해도 돼",
                "\"반말해도 돼\"", "내 이름은 민수야", "민수가 아니라 지수라고 불러 줘", "장난하지 마", "존댓말로 해 주세요", "해리야 안녕", "기억나?", "", "해리"))
            assertNull(VoiceConfirmation.request(said), said);
        assertNull(VoiceConfirmation.request(null)); assertNull(VoiceConfirmation.request("반말해도 돼 ".repeat(20)));
    }
    @Test void everyQuestionIsOneBoundedSentenceSoItCountsAsHeardOnlyInFull() {
        var proposals = List.of(Proposal.name("민수"), Proposal.name("Minsu"), VoiceConfirmation.CASUAL_SPEECH, VoiceConfirmation.JOKES,
                Proposal.forget(ConfirmedTextForget.Target.ALL), Proposal.forget(ConfirmedTextForget.Target.NAME),
                Proposal.forget(ConfirmedTextForget.Target.SPEECH), Proposal.forget(ConfirmedTextForget.Target.JOKE));
        for (var proposal : proposals) for (boolean casual : new boolean[]{false, true}) {
            String question = VoiceConfirmation.question(proposal, casual);
            assertEquals(1, SentenceChunks.split(question).size(), question); assertTrue(question.length() <= 240, question);
            assertTrue(question.endsWith("?"), question);
            assertTrue(VoiceConfirmation.declined(proposal, casual).length() <= 240);
        }
        assertEquals("민수님이라고 부르면 될까요?", VoiceConfirmation.question(Proposal.name("민수"), false));
        assertEquals("민수라고 부르면 될까?", VoiceConfirmation.question(Proposal.name("민수"), true));
        assertEquals("지훈이라고 부르면 될까?", VoiceConfirmation.question(Proposal.name("지훈"), true));
        assertTrue(VoiceConfirmation.question(VoiceConfirmation.CASUAL_SPEECH, false).contains("반말"));
        assertTrue(VoiceConfirmation.question(Proposal.forget(ConfirmedTextForget.Target.ALL), false).contains("모두 지울까요"));
    }
    @Test void aConfirmedProposalMapsToExactlyOneStoredSettingAndADeletionToNone() {
        var name = VoiceConfirmation.change(Proposal.name("민수"));
        assertEquals(DiscordMemory.Kind.NAME, name.kind()); assertEquals("민수", name.value()); assertEquals("preferred", name.label());
        var speech = VoiceConfirmation.change(VoiceConfirmation.CASUAL_SPEECH);
        assertEquals(DiscordMemory.Kind.SPEECH_AGREEMENT, speech.kind()); assertEquals("ALLOWED", speech.value());
        var jokes = VoiceConfirmation.change(VoiceConfirmation.JOKES);
        assertEquals(DiscordMemory.Kind.AVOID_JOKE, jokes.kind()); assertTrue(jokes.jokesAllowed());
        assertNull(VoiceConfirmation.change(Proposal.forget(ConfirmedTextForget.Target.ALL)));
    }
    @Test void aProposalCannotCarryAnEmptyValueOrAnUnknownDeletionScope() {
        assertThrows(IllegalArgumentException.class, () -> Proposal.name(" "));
        assertThrows(IllegalArgumentException.class, () -> Proposal.name("가".repeat(21)));
        assertThrows(IllegalArgumentException.class, () -> new Proposal(Proposal.Kind.FORGET, "EVERYONE"));
        assertThrows(NullPointerException.class, () -> new Proposal(null, "x"));
        assertEquals(ConfirmedTextForget.Target.JOKE, Proposal.forget(ConfirmedTextForget.Target.JOKE).target());
    }
}
