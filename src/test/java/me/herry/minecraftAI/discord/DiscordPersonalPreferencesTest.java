package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
    @Test void settingsQueryDoesNotCallModelAndQueuedReplyIsRetiredByDeletionOrRestore() throws Exception {
        try (var session = session(store(), request -> { throw new AssertionError("Settings query must not call model"); })) {
            session.confirmedName("A", "민수", "name-a").get(3, TimeUnit.SECONDS);
            session.confirmedName("B", "지수", "name-b").get(3, TimeUnit.SECONDS);
            session.confirmedJokes("A", false, "joke-a").get(3, TimeUnit.SECONDS);
            var own = session.personalSettings("A").get(3, TimeUnit.SECONDS);
            assertTrue(session.personalCurrent(own)); assertTrue(own.text().contains("민수")); assertFalse(own.text().contains("지수"));
            var other = session.personalSettings("B").get(3, TimeUnit.SECONDS);
            assertTrue(other.text().contains("지수")); assertFalse(other.text().contains("민수")); assertFalse(other.text().contains("장난 중단"));
            String backup = session.backup().get(3, TimeUnit.SECONDS); assertNotNull(backup);
            session.forget("A").get(3, TimeUnit.SECONDS); assertFalse(session.personalCurrent(own));
            var cleared = session.personalSettings("A").get(3, TimeUnit.SECONDS);
            assertTrue(cleared.text().contains("저장한 호칭 없음")); assertFalse(cleared.text().contains("민수"));
            session.restoreBackup(backup).get(3, TimeUnit.SECONDS); assertFalse(session.personalCurrent(cleared));
            assertFalse(session.personalSettings("A").get(3, TimeUnit.SECONDS).text().contains("민수"));
            session.close(); assertFalse(session.personalCurrent(other));
        }
    }
}
