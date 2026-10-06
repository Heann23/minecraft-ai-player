package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static me.herry.minecraftAI.discord.ConversationTurns.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationInputTest {
    private final Set<String> members = Set.of("A", "B", "C");
    private final Map<String, String> names = Map.of("민수", "B", "수빈", "C");
    @Test void honorificsCannotOverrideExplicitHumanAddressee() {
        assertEquals(Address.OTHER_USER, AddresseeResolver.resolve("민수님 뭐 하세요?", "A", members, names).address());
        assertEquals("B", AddresseeResolver.resolve("민수님 뭐 하세요?", "A", members, names).otherUserId());
    }
    @Test void newParticipantsAreCandidatesAndLeavingPeopleAreExcluded() {
        assertEquals("C", AddresseeResolver.resolve("수빈아 안녕", "A", members, names).otherUserId());
        assertEquals(Address.UNKNOWN, AddresseeResolver.resolve("수빈아 안녕", "A", Set.of("A", "B"), names).address());
    }
    @Test void casualCallToHerryAndHonorificHumanConversationRemainDistinct() {
        assertEquals(Address.CHARACTER, AddresseeResolver.resolve("해리야 뭐해", "A", members, names).address());
        assertEquals(Address.UNKNOWN, AddresseeResolver.resolve("뭐 하세요?", "A", members, names).address());
        assertEquals(Address.UNKNOWN, AddresseeResolver.resolve("야 뭐해", "A", members, names).address());
    }
    @Test void quotationAndNameSubstringDoNotBecomeCalls() {
        assertEquals(Address.UNKNOWN, AddresseeResolver.resolve("\"해리야 뭐해\"라고 민수가 말했다", "A", members, names).address());
        assertEquals(Address.UNKNOWN, AddresseeResolver.resolve("해리포터 봤어?", "A", members, names).address());
    }
    @Test void recipientModelCannotInventParticipantOrDeclareCharacterAddress() {
        assertEquals(Address.UNKNOWN, AddresseeResolver.validateSuggestion("unknown-user", members).address());
        assertEquals(Address.UNKNOWN, AddresseeResolver.validateSuggestion("herry", members).address());
        assertEquals(Address.OTHER_USER, AddresseeResolver.validateSuggestion("B", members).address());
    }
    @Test void nameComesFromExplicitSelfIntroductionOrPreferredCallOnly() {
        assertEquals("민수", ConfirmedMemoryInput.introducedName("내 이름은 민수야", true).orElseThrow());
        assertEquals("수빈", ConfirmedMemoryInput.introducedName("제 이름은 수빈입니다.", true).orElseThrow());
        assertEquals("돌돌", ConfirmedMemoryInput.introducedName("돌돌이라고 불러줘", true).orElseThrow());
    }
    @Test void quotedOtherNamesAndUnreliableRecognitionAreNotPersistable() {
        assertTrue(ConfirmedMemoryInput.introducedName("민수는 수빈이라고 불러줘", true).isEmpty());
        assertTrue(ConfirmedMemoryInput.introducedName("\"내 이름은 민수야\"", true).isEmpty());
        assertTrue(ConfirmedMemoryInput.introducedName("내 이름은 민수야", false).isEmpty());
    }
    @Test void consentRequiresExplicitAnswerAndCorrectPendingTarget() {
        var turns = new ConversationTurns(() -> 1000, 60_000, 8); turns.join("A"); turns.join("B");
        var question = turns.accept("A", "q", "해리 안녕", Address.CHARACTER, false).token();
        turns.generated(question, "반말해도 될까요?"); turns.played(question, "반말해도 될까요?".length()); turns.askCasualPermission(question, 30_000); turns.finish(question);
        var other = turns.accept("B", "a", "응", Address.CHARACTER, false).token();
        assertFalse(turns.answerCasualPermission(other, ConfirmedMemoryInput.consent("응", true).isPresent()));
        var target = turns.accept("A", "a", "아니요", Address.UNKNOWN, false).token();
        var answer = ConfirmedMemoryInput.consent("아니요", true).orElseThrow();
        assertTrue(turns.answerCasualPermission(target, true)); assertEquals(ConfirmedMemoryInput.Consent.REFUSED, answer); turns.close();
        assertTrue(ConfirmedMemoryInput.consent("해리 너 지금 뭐해", true).isEmpty());
        assertTrue(ConfirmedMemoryInput.consent("응", false).isEmpty());
    }
}
