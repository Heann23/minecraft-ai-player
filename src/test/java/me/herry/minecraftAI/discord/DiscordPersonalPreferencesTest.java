package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordPersonalPreferencesTest {
    @TempDir Path directory;
    private DiscordSettings settings() {
        return new DiscordSettings(true, "TEST_TOKEN", "12345678901234567", "12345678901234568", "herry", false, 60000, 32,
                new DiscordMemoryStore.BackupPolicy(false, 600000, 24, 7, 128L * 1024 * 1024));
    }
    private DiscordMemory.Subject subject(String user) { return new DiscordMemory.Subject(settings().guildId(), "herry", user); }
    private DiscordMemoryStore store() throws Exception { return new DiscordMemoryStore(directory, settings().backup(), () -> 1000); }
    private DiscordSession session(DiscordMemoryStore store, ResponsePipeline.Model model) {
        return new DiscordSession(settings(), store, (pcm, language) -> { throw new AssertionError("No voice input expected"); }, model,
                text -> new byte[PcmAudio.FRAME_BYTES], code -> fail(code), () -> 1000, () -> 1000, false);
    }
    @Test void explicitJokePreferencePersistsForOwnUserWithoutGrantingCasualSpeech() throws Exception {
        try (var session = session(store(), request -> "답변")) {
            session.confirmedJokes("A", false, "joke-one").get(3, TimeUnit.SECONDS);
        }
        try (var reopened = store()) {
            var facts = reopened.visible(subject("A"), Set.of("A", "B")).get(3, TimeUnit.SECONDS);
            assertEquals(1, facts.size()); assertEquals(DiscordMemory.Kind.AVOID_JOKE, facts.getFirst().key().kind());
            assertEquals("all", facts.getFirst().key().label()); assertEquals("AVOID", facts.getFirst().value());
            assertEquals(DiscordMemory.Evidence.EXPLICIT, facts.getFirst().evidence());
            assertTrue(reopened.visible(subject("B"), Set.of("A", "B")).get(3, TimeUnit.SECONDS).isEmpty());
        }
    }
    @Test void preferenceChangeCancelsOldTextAndFreshRequestSeesOnlyLatestConfirmedValue() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var freshRequest = new AtomicReference<ResponsePipeline.Request>();
        try (var session = session(store(), request -> {
            if (DialogueContext.currentInput(request).equals("옛 질문")) {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
            }
            freshRequest.set(request); return "담백한 답변";
        })) {
            var old = session.textReply("A", "옛 질문", "old"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            session.confirmedJokes("A", false, "joke-new").get(3, TimeUnit.SECONDS);
            assertTrue(interrupted.await(3, TimeUnit.SECONDS)); assertTrue(old.isCompletedExceptionally());
            var fresh = session.textReply("A", "새 질문", "new").get(3, TimeUnit.SECONDS);
            assertTrue(session.textCurrent(fresh)); assertEquals(1, freshRequest.get().context().size());
            assertEquals("AVOID", freshRequest.get().memory().getFirst().value()); session.textSubmitted(fresh);
        }
    }
    @Test void correctionAndDeletionSurviveOldBackupRestoreAndRestart() throws Exception {
        var store = store();
        try (var session = session(store, request -> "답변")) {
            session.confirmedJokes("A", false, "avoid").get(3, TimeUnit.SECONDS);
            String backup = session.backup().get(3, TimeUnit.SECONDS); assertNotNull(backup);
            session.confirmedJokes("A", true, "allow").get(3, TimeUnit.SECONDS);
            String allowedBackup = session.backup().get(3, TimeUnit.SECONDS); assertNotNull(allowedBackup);
            session.restoreBackup(backup).get(3, TimeUnit.SECONDS);
            assertEquals("ALLOWED", store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).getFirst().value());
            session.confirmedJokes("A", false, "avoid-again").get(3, TimeUnit.SECONDS);
            session.restoreBackup(allowedBackup).get(3, TimeUnit.SECONDS);
            assertEquals("AVOID", store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).getFirst().value());
            session.forget("A").get(3, TimeUnit.SECONDS); session.restoreBackup(backup).get(3, TimeUnit.SECONDS);
            assertTrue(store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).isEmpty());
        }
        try (var reopened = store()) { assertTrue(reopened.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).isEmpty()); }
    }
}
