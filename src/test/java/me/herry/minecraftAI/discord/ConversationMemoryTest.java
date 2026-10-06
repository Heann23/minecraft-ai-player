package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Set;
import static me.herry.minecraftAI.discord.DiscordMemory.*;
import static me.herry.minecraftAI.discord.ConversationTurns.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {
    @TempDir Path directory;
    private final Subject A = new Subject("guild", "herry", "A");
    private final Subject B = new Subject("guild", "herry", "B");
    private DiscordMemoryStore store() throws Exception { return new DiscordMemoryStore(directory,
            new DiscordMemoryStore.BackupPolicy(false, 600_000, 24, 7, 128L * 1024 * 1024), () -> 1000); }
    private ConversationTurns turns() { var turns = new ConversationTurns(() -> 1000, 60_000, 32); turns.join("A"); turns.join("B"); return turns; }
    private Token accept(ConversationTurns turns, String speaker, String text, String id) { return turns.accept(speaker, id, text, Address.CHARACTER, false).token(); }
    private void ask(ConversationTurns turns) {
        var question = accept(turns, "A", "해리야", "q"); turns.generated(question, "반말해도 될까요?");
        turns.played(question, "반말해도 될까요?".length()); assertTrue(turns.askCasualPermission(question, 30_000)); turns.finish(question);
    }
    @Test void unreliableSpeechStoresOnlyTighteningRequests() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store);
            var stop = accept(turns, "A", "해리야 장난하지 마", "1");
            assertEquals("AVOID", memory.captureTightening(stop, A, "해리야 장난하지 마", "1").join().value());
            var polite = accept(turns, "A", "존댓말로 해주세요", "2");
            assertEquals("REFUSED", memory.captureTightening(polite, A, "존댓말로 해주세요", "2").join().value());
            var facts = store.visible(A, Set.of("A")).join();
            assertTrue(facts.stream().anyMatch(fact -> fact.key().kind() == Kind.AVOID_JOKE && fact.value().equals("AVOID")));
            assertTrue(facts.stream().anyMatch(fact -> fact.key().kind() == Kind.SPEECH_AGREEMENT && fact.value().equals("REFUSED")));
            int id = 10;
            for (String loosening : java.util.List.of("장난해도 돼요", "나한테 반말해도 돼요", "내 이름은 민수야", "해리야 안녕", "장난하지 마?", "친구가 장난하지 마 라고 했어")) {
                String utterance = "x" + id++; var token = accept(turns, "A", loosening, utterance);
                assertNotNull(token, loosening); assertNull(memory.captureTightening(token, A, loosening, utterance), loosening);
            }
            assertEquals(2, store.visible(A, Set.of("A")).join().size());
        } finally { turns.close(); }
    }
    @Test void tighteningNeedsTheSpeakersOwnCurrentTurnAndExactText() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store); var token = accept(turns, "A", "장난하지 마", "1");
            assertNull(memory.captureTightening(token, B, "장난하지 마", "1"));
            assertNull(memory.captureTightening(token, A, "장난하지 마", "2"));
            assertNull(memory.captureTightening(token, A, "장난하지 마세요", "1"));
            assertNull(memory.captureTightening(null, A, "장난하지 마", "1"));
            accept(turns, "A", "해리야 다음", "3"); assertNull(memory.captureTightening(token, A, "장난하지 마", "1"));
            var again = accept(turns, "A", "장난하지 마", "9"); memory.forget(A).join();
            assertNull(memory.captureTightening(again, A, "장난하지 마", "9"));
            assertTrue(store.snapshot().join().facts().isEmpty());
        } finally { turns.close(); }
    }
    private void askName(ConversationTurns turns, String candidate, String id) {
        var question = accept(turns, "A", "해리야 내 이름은 " + candidate + "야", id);
        String text = candidate + "님이라고 부르면 될까요?"; turns.generated(question, text); turns.played(question, text.length());
        assertTrue(turns.askConfirmation(question, VoiceConfirmation.Proposal.name(candidate), 30_000)); turns.finish(question);
    }
    private void ask(ConversationTurns turns, VoiceConfirmation.Proposal proposal, String id) {
        var question = accept(turns, "A", "해리야 부탁이 있어", id); String text = VoiceConfirmation.question(proposal, false);
        turns.generated(question, text); turns.played(question, text.length());
        assertTrue(turns.askConfirmation(question, proposal, 30_000)); turns.finish(question);
    }
    @Test void spokenLooseningIsOnlyAProposalOfTheSpeakersOwnCurrentTurn() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store);
            var token = accept(turns, "A", "해리야 반말해도 돼", "1");
            assertEquals(VoiceConfirmation.CASUAL_SPEECH, memory.loosening(token, A, "해리야 반말해도 돼", "1"));
            assertNull(memory.loosening(token, B, "해리야 반말해도 돼", "1")); assertNull(memory.loosening(token, A, "해리야 반말해도 돼", "2"));
            assertNull(memory.loosening(null, A, "해리야 반말해도 돼", "1"));
            var later = accept(turns, "A", "해리야 다음", "3"); assertNull(memory.loosening(token, A, "해리야 반말해도 돼", "1"));
            assertNull(memory.loosening(later, A, "해리야 다음", "3"));
            assertTrue(store.snapshot().join().facts().isEmpty());
        } finally { turns.close(); }
    }
    @Test void spokenYesAppliesCasualSpeechAndJokesOnlyForTheAskedUser() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store);
            ask(turns, VoiceConfirmation.CASUAL_SPEECH, "q1");
            assertNull(memory.answerConfirmation(accept(turns, "B", "응", "b1"), B, "응", "b1"));
            var yes = accept(turns, "A", "응", "a1"); var speech = memory.answerConfirmation(yes, A, "응", "a1");
            assertEquals(ConversationMemory.Answer.CONFIRMED, speech.answer()); speech.saved().join();
            assertTrue(DiscordPersonalSettings.casual(A, store.visible(A, Set.of("A")).join(), 1000));
            ask(turns, VoiceConfirmation.JOKES, "q2");
            var no = accept(turns, "A", "아니", "a2"); var declined = memory.answerConfirmation(no, A, "아니", "a2");
            assertEquals(ConversationMemory.Answer.DECLINED, declined.answer()); declined.saved().join();
            assertTrue(store.visible(A, Set.of("A")).join().stream().noneMatch(fact -> fact.key().kind() == Kind.AVOID_JOKE));
            ask(turns, VoiceConfirmation.JOKES, "q3");
            var again = accept(turns, "A", "좋아요", "a3"); memory.answerConfirmation(again, A, "좋아요", "a3").saved().join();
            assertTrue(store.visible(A, Set.of("A")).join().stream().anyMatch(fact -> fact.key().kind() == Kind.AVOID_JOKE && fact.value().equals("ALLOWED")));
            assertTrue(store.visible(B, Set.of("B")).join().isEmpty());
        } finally { turns.close(); }
    }
    @Test void spokenYesErasesOnlyTheConfirmedScopeAndKeepsTheAnswerTurn() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store);
            store.remember(new Key(A, Kind.NAME, "", "preferred"), "민수", Evidence.EXPLICIT, "n", 0).join();
            store.remember(new Key(A, Kind.AVOID_JOKE, "", "all"), "AVOID", Evidence.EXPLICIT, "j", 0).join();
            store.remember(new Key(B, Kind.NAME, "", "preferred"), "지수", Evidence.EXPLICIT, "b", 0).join();
            ask(turns, VoiceConfirmation.Proposal.forget(ConfirmedTextForget.Target.NAME), "q1");
            var yes = accept(turns, "A", "네", "a1"); var erased = memory.answerConfirmation(yes, A, "네", "a1"); erased.saved().join();
            assertTrue(turns.isCurrent(yes)); assertTrue(turns.context().isEmpty());
            var left = store.visible(A, Set.of("A")).join();
            assertEquals(1, left.size()); assertEquals(Kind.AVOID_JOKE, left.getFirst().key().kind());
            turns.finish(yes);
            ask(turns, VoiceConfirmation.Proposal.forget(ConfirmedTextForget.Target.ALL), "q2");
            var all = accept(turns, "A", "응", "a2"); memory.answerConfirmation(all, A, "응", "a2").saved().join();
            assertTrue(store.visible(A, Set.of("A")).join().isEmpty());
            assertEquals("지수", store.visible(B, Set.of("B")).join().getFirst().value());
        } finally { turns.close(); }
    }
    @Test void spokenNameCorrectionIsACandidateToo() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store); int id = 0;
            for (String said : java.util.List.of("해리야 내 이름은 민수가 아니라 지수야", "해리야. 앞으로 지수라고 불러 줘", "나를 지수라고 불러 주세요")) {
                String utterance = "c" + id++; var token = accept(turns, "A", said, utterance);
                assertEquals("지수", memory.nameCandidate(token, A, said, utterance), said);
            }
            assertTrue(store.snapshot().join().facts().isEmpty());
        } finally { turns.close(); }
    }
    @Test void spokenNameIsOnlyACandidateFromAStrictOwnIntroduction() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store); int id = 0;
            var ok = accept(turns, "A", "해리야 내 이름은 민수야", "ok");
            assertEquals("민수", memory.nameCandidate(ok, A, "해리야 내 이름은 민수야", "ok"));
            assertNull(memory.nameCandidate(ok, B, "해리야 내 이름은 민수야", "ok")); assertNull(memory.nameCandidate(ok, A, "해리야 내 이름은 민수야", "other"));
            assertNull(memory.nameCandidate(null, A, "해리야 내 이름은 민수야", "ok"));
            for (String bad : java.util.List.of("친구 이름은 민수야", "내 이름은 해리야", "내 이름은 해리님이야", "\"내 이름은 민수야\"라고 했어", "내 이름은 민수야 그리고 철수야", "내 이름은 민수야?", "해리야 안녕")) {
                String utterance = "b" + id++; var token = accept(turns, "A", bad, utterance);
                assertNotNull(token, bad); assertNull(memory.nameCandidate(token, A, bad, utterance), bad);
            }
            assertTrue(store.snapshot().join().facts().isEmpty());
        } finally { turns.close(); }
    }
    @Test void spokenYesStoresTheEchoedNameOnlyForTheAskedUser() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store); askName(turns, "민수", "q");
            var other = accept(turns, "B", "응", "b1"); assertNull(memory.answerConfirmation(other, B, "응", "b1"));
            var yes = accept(turns, "A", "네.", "a1"); var reply = memory.answerConfirmation(yes, A, "네.", "a1");
            assertEquals(ConversationMemory.Answer.CONFIRMED, reply.answer()); assertEquals(VoiceConfirmation.Proposal.name("민수"), reply.proposal()); reply.saved().join();
            assertEquals("민수", store.visible(A, Set.of("A")).join().getFirst().value()); assertTrue(store.visible(B, Set.of("B")).join().isEmpty());
            assertNull(memory.answerConfirmation(accept(turns, "A", "응", "a2"), A, "응", "a2"));
        } finally { turns.close(); }
    }
    @Test void spokenNoOrAnythingElseStoresNothing() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store);
            askName(turns, "민수", "q1"); var no = accept(turns, "A", "아니요", "n1"); var declined = memory.answerConfirmation(no, A, "아니요", "n1");
            assertEquals(ConversationMemory.Answer.DECLINED, declined.answer()); declined.saved().join();
            askName(turns, "민수", "q2"); var vague = accept(turns, "A", "음 글쎄요", "m1"); assertNull(memory.answerConfirmation(vague, A, "음 글쎄요", "m1"));
            var late = accept(turns, "A", "응", "m2"); assertNull(memory.answerConfirmation(late, A, "응", "m2"));
            askName(turns, "민수", "q3"); var asking = accept(turns, "A", "응?", "m3"); assertNull(memory.answerConfirmation(asking, A, "응?", "m3"));
            assertTrue(store.snapshot().join().facts().isEmpty());
        } finally { turns.close(); }
    }
    @Test void wrongSpeakerAndUnconfirmedSpeechCannotSaveName() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store); var token = accept(turns, "A", "내 이름은 민수야", "1");
            memory.capture(token, B, "내 이름은 민수야", true, "1").join();
            memory.capture(token, A, "내 이름은 민수야", false, "1").join(); assertTrue(store.snapshot().join().facts().isEmpty());
            memory.capture(token, A, "내 이름은 민수야", true, "1").join(); assertEquals("민수", store.visible(A, Set.of("A")).join().getFirst().value());
        } finally { turns.close(); }
    }
    @Test void anotherUsersYesDoesNotGrantConsentAndTargetsRefusalPersistsAcrossRestart() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store); ask(turns);
            var other = accept(turns, "B", "응", "b1"); memory.capture(other, B, "응", true, "b1").join(); assertTrue(store.snapshot().join().facts().isEmpty());
            var target = accept(turns, "A", "아니요", "a1"); memory.capture(target, A, "아니요", true, "a1").join();
        } finally { turns.close(); }
        try (var store = store()) { assertEquals("REFUSED", store.visible(A, Set.of("A")).join().getFirst().value()); assertTrue(store.visible(B, Set.of("B")).join().isEmpty()); }
    }
    @Test void casualAnswerWithoutPendingQuestionAndExpiredTurnCannotCreateConsent() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store); var token = accept(turns, "A", "응", "1"); memory.capture(token, A, "응", true, "1").join();
            assertTrue(store.snapshot().join().facts().isEmpty());
            var old = accept(turns, "A", "내 이름은 민수야", "2"); accept(turns, "A", "아니 그게 아니라", "3");
            memory.capture(old, A, "내 이름은 민수야", true, "2").join(); assertTrue(store.snapshot().join().facts().isEmpty());
        } finally { turns.close(); }
    }
    @Test void sameTurnCannotCaptureAnotherUtteranceOrInventedText() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store); var token = accept(turns, "A", "해리 안녕", "1");
            memory.capture(token, A, "내 이름은 민수야", true, "1").join();
            memory.capture(token, A, "해리 안녕", true, "other").join(); assertTrue(store.snapshot().join().facts().isEmpty());
        } finally { turns.close(); }
    }
    @Test void deletionClearsContextInvalidatesGeneratedAnswerAndCannotBeUndoneByBackup() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store); var token = accept(turns, "A", "내 이름은 민수야", "1");
            memory.capture(token, A, "내 이름은 민수야", true, "1").join(); var backup = store.backup().join();
            turns.generated(token, "민수님 반가워요"); memory.forget(A).join();
            assertFalse(turns.isCurrent(token)); assertTrue(turns.context().isEmpty()); store.restore(backup).join(); assertTrue(store.snapshot().join().facts().isEmpty());
        } finally { turns.close(); }
    }
    @Test void concurrentCaptureAndDeletionCannotResurrectOldInput() throws Exception {
        var turns = turns();
        try (var store = store()) {
            var memory = new ConversationMemory(turns, store);
            for (int i = 0; i < 40; i++) {
                String utterance = "r" + i; var token = accept(turns, "A", "내 이름은 민수야", utterance);
                var start = new java.util.concurrent.CountDownLatch(1);
                var capture = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try { start.await(); memory.capture(token, A, "내 이름은 민수야", true, utterance).join(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                });
                var deletion = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try { start.await(); memory.forget(A).join(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                });
                start.countDown(); java.util.concurrent.CompletableFuture.allOf(capture, deletion).get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(store.snapshot().join().facts().isEmpty());
            }
        } finally { turns.close(); }
    }
}
