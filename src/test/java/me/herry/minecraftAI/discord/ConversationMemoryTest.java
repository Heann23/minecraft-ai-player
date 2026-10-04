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
