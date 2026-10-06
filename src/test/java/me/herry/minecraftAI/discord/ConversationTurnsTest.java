package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static me.herry.minecraftAI.discord.ConversationTurns.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationTurnsTest {
    private long now = 1000;
    private ConversationTurns turns;
    @BeforeEach void setup() { turns = new ConversationTurns(() -> now, 60_000, 4); turns.join("A"); turns.join("B"); }
    private Token call(String user, String id) { return turns.accept(user, id, "해리 뭐해?", Address.CHARACTER, false).token(); }
    private Token question() {
        Token token = call("A", "q");
        turns.generated(token, "말 편하게 해도 될까요?"); turns.played(token, "말 편하게 해도 될까요?".length());
        assertTrue(turns.askCasualPermission(token, 10_000)); turns.finish(token); return token;
    }
    @Test void followsUpWithoutRepeatedName() {
        call("A", "1");
        assertEquals(Decision.RESPOND, turns.accept("A", "2", "그건 왜?", Address.UNKNOWN, false).decision());
        assertEquals(Decision.IGNORE, turns.accept("B", "1", "그건 왜?", Address.UNKNOWN, false).decision());
    }
    @Test void explicitOtherRecipientEndsParticipation() {
        Token old = call("A", "1");
        assertEquals(Decision.IGNORE, turns.accept("A", "2", "민수님 뭐해요?", Address.OTHER_USER, false).decision());
        assertFalse(turns.isCurrent(old));
        assertEquals(Decision.IGNORE, turns.accept("A", "3", "왜?", Address.UNKNOWN, false).decision());
    }
    @Test void followupExpires() { call("A", "1"); now += 60_000; assertEquals(Decision.IGNORE, turns.accept("A", "2", "왜?", Address.UNKNOWN, false).decision()); }
    @Test void duplicateUtteranceDoesNotInvalidateOriginal() {
        Token token = call("A", "1"); assertNull(call("A", "1")); assertTrue(turns.isCurrent(token));
    }
    @Test void correctionRejectsLateGenerationAndPlayback() {
        Token old = call("A", "1"); Token fresh = call("A", "2");
        assertFalse(turns.generated(old, "옛 답변")); assertFalse(turns.played(old, 1)); assertTrue(turns.isCurrent(fresh));
    }
    @Test void onsetCancelsBeforeTranscription() { Token old = call("A", "1"); assertTrue(turns.speechStarted("A")); assertFalse(turns.isCurrent(old)); }
    @Test void unrelatedOnsetDoesNotCancel() { Token old = call("A", "1"); assertFalse(turns.speechStarted("B")); assertTrue(turns.isCurrent(old)); }
    @Test void stopRejectsLateOutput() {
        Token old = call("A", "1"); assertEquals(Decision.STOPPED, turns.accept("A", "2", "그만", Address.UNKNOWN, true).decision()); assertFalse(turns.generated(old, "답"));
    }
    @Test void partialPlaybackOnlyIsRemembered() {
        Token old = call("A", "1"); turns.generated(old, "첫 문장. 아직 안 들은 말."); turns.played(old, 5); call("A", "2");
        assertTrue(turns.context().stream().anyMatch(l -> l.assistant() && l.text().equals("첫 문장.") && l.target().equals("A")));
        assertFalse(turns.context().stream().anyMatch(l -> l.text().contains("아직")));
    }
    @Test void generatedUnheardAnswerNeverBecomesContext() {
        Token old = call("A", "1"); turns.generated(old, "들리지 않은 약속"); turns.finish(old);
        assertFalse(turns.context().stream().anyMatch(Line::assistant));
    }
    @Test void anotherUsersConsentIsNotAppliedAndTargetCanStillAnswer() {
        question(); Token other = call("B", "answer"); assertFalse(turns.answerCasualPermission(other, true));
        Token target = turns.accept("A", "answer", "응", Address.UNKNOWN, false).token(); assertTrue(turns.answerCasualPermission(target, true));
        assertFalse(turns.answerCasualPermission(target, true));
    }
    @Test void ambiguousConsentIsNotApplied() { question(); Token answer = call("A", "answer"); assertFalse(turns.answerCasualPermission(answer, false)); }
    @Test void consentExpiresAndCannotApplyToLaterTurns() { question(); call("A", "unrelated"); assertFalse(turns.answerCasualPermission(call("A", "later"), true)); }
    @Test void consentTimeExpires() { question(); now += 10_000; assertFalse(turns.answerCasualPermission(call("A", "later"), true)); }
    @Test void unheardPermissionQuestionCannotBeRegistered() { Token token = call("A", "1"); turns.generated(token, "허락 질문"); assertFalse(turns.askCasualPermission(token, 1000)); }
    @Test void leaveAndRejoinRejectsOldRoute() { Token token = call("A", "1"); turns.leave("A"); turns.join("A"); assertFalse(turns.isCurrent(token)); assertTrue(turns.context().isEmpty()); }
    @Test void deletionInvalidatesAllPendingContext() { Token token = call("B", "1"); turns.forget("A"); assertFalse(turns.isCurrent(token)); assertTrue(turns.context().isEmpty()); }
    @Test void quietIgnoresFollowupsButAllowsDirectQuestion() { call("A", "1"); turns.quiet(true); assertNull(turns.accept("A", "2", "왜?", Address.UNKNOWN, false).token()); assertNotNull(call("A", "3")); }
    @Test void contextIsBoundedAndCloseIsFinal() { for (int i = 0; i < 10; i++) call("A", "" + i); assertEquals(4, turns.context().size()); turns.close(); assertNull(call("A", "last")); assertThrows(IllegalStateException.class, () -> turns.join("C")); }
    private Token askedName(String user, String id) {
        Token question = call(user, id); String text = "민수님이라고 부르면 될까요?"; turns.generated(question, text); turns.played(question, text.length());
        assertTrue(turns.askConfirmation(question, VoiceConfirmation.Proposal.name("민수"), 10_000)); turns.finish(question); return question;
    }
    @Test void nameConfirmationNeedsAFullyHeardQuestionAndTheTargetsNextTurnOnly() {
        Token unheard = call("A", "u"); turns.generated(unheard, "민수님이라고 부르면 될까요?");
        assertFalse(turns.askConfirmation(unheard, VoiceConfirmation.Proposal.name("민수"), 10_000)); turns.finish(unheard);
        Token heard = call("A", "h"); turns.generated(heard, "질문"); turns.played(heard, 2);
        assertFalse(turns.askConfirmation(heard, null, 10_000)); assertFalse(turns.askConfirmation(heard, VoiceConfirmation.JOKES, 0)); turns.finish(heard);
        askedName("A", "q");
        assertNull(turns.answerConfirmation(call("B", "b")));
        Token answer = call("A", "a"); assertEquals(VoiceConfirmation.Proposal.name("민수"), turns.answerConfirmation(answer)); assertNull(turns.answerConfirmation(answer));
    }
    @Test void nameConfirmationLapsesWithTimeLaterTurnsLeaveForgetAndQuiet() {
        askedName("A", "q1"); now += 10_000; assertNull(turns.answerConfirmation(call("A", "late")));
        askedName("A", "q2"); call("A", "other"); assertNull(turns.answerConfirmation(call("A", "later")));
        askedName("A", "q3"); turns.leave("A"); turns.join("A"); assertNull(turns.answerConfirmation(call("A", "after-leave")));
        askedName("A", "q4"); turns.forget("A"); assertNull(turns.answerConfirmation(call("A", "after-forget")));
        askedName("A", "q5"); turns.quiet(true); assertNull(turns.answerConfirmation(call("A", "after-quiet")));
    }
    @Test void aNewerQuestionReplacesTheOlderOneAndAConfirmedDeletionKeepsOnlyTheAnswerTurn() {
        askedName("A", "q1");
        Token second = call("A", "q2"); String text = "모두 지울까요?"; turns.generated(second, text); turns.played(second, text.length());
        var forget = VoiceConfirmation.Proposal.forget(ConfirmedTextForget.Target.ALL);
        assertTrue(turns.askConfirmation(second, forget, 10_000)); turns.finish(second);
        Token yes = turns.accept("A", "y", "응", Address.UNKNOWN, false).token();
        assertEquals(forget, turns.answerConfirmation(yes)); assertFalse(turns.context().isEmpty());
        assertTrue(turns.clearContextFor(yes)); assertTrue(turns.context().isEmpty()); assertTrue(turns.isCurrent(yes));
        assertTrue(turns.generated(yes, "지웠어요.")); assertTrue(turns.played(yes, 5)); turns.finish(yes);
        assertEquals(java.util.List.of("지웠어요."), turns.context().stream().map(Line::text).toList());
    }
    @Test void tokenFromDifferentSessionCannotReplay() { Token token = call("A", "1"); var other = new ConversationTurns(() -> now, 60_000, 4); other.join("A"); assertFalse(other.isCurrent(token)); other.close(); }
}
