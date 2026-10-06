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
    @Test void controlAcknowledgementKeepsDeliveryButLeavesTranscriptUntouched() {
        Token earlier = turns.accept("A", "q", "첫 질문", Address.CHARACTER, false).token();
        Token control = turns.accept("A", "c", "답변 취소해줘", Address.CHARACTER, false).token();
        assertFalse(turns.isCurrent(earlier)); assertTrue(turns.acknowledgeControl(control, "중단했어요."));
        assertEquals(java.util.List.of("첫 질문"), turns.context().stream().map(Line::text).toList());
        assertTrue(turns.played(control, "중단했어요.".length())); turns.finish(control);
        assertEquals(java.util.List.of("첫 질문"), turns.context().stream().map(Line::text).toList()); assertFalse(turns.busy());
    }
    @Test void controlAcknowledgementRejectsRetiredTurnAndSecondAnswer() {
        Token old = call("A", "1"); Token fresh = call("A", "2");
        assertFalse(turns.acknowledgeControl(old, "늦은 응답")); assertTrue(turns.acknowledgeControl(fresh, "응답"));
        assertFalse(turns.acknowledgeControl(fresh, "또 응답")); assertFalse(turns.generated(fresh, "또 응답"));
    }
    @Test void controlAcknowledgementDoesNotLeakIntoTheNextAnswer() {
        Token control = call("A", "1"); turns.acknowledgeControl(control, "응답."); turns.played(control, 3); turns.finish(control);
        Token next = call("A", "2"); turns.generated(next, "정상 답변"); turns.played(next, 5); turns.finish(next);
        assertTrue(turns.context().stream().anyMatch(l -> l.assistant() && l.text().equals("정상 답변")));
    }
    @Test void tokenFromDifferentSessionCannotReplay() { Token token = call("A", "1"); var other = new ConversationTurns(() -> now, 60_000, 4); other.join("A"); assertFalse(other.isCurrent(token)); other.close(); }
}
