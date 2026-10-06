package me.herry.minecraftAI.discord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static me.herry.minecraftAI.discord.DiscordMemory.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscordBackupControlsTest {
    @TempDir Path directory;
    private static final String GUILD = "12345678901234567", USER = "A";
    private static final Subject PERSON = new Subject(GUILD, "herry", USER);
    private static final Key NAME = new Key(PERSON, Kind.NAME, "", "preferred");
    private static final Key STYLE = new Key(PERSON, Kind.SPEECH_AGREEMENT, "", "casual");
    private DiscordSettings settings() {
        return new DiscordSettings(true, "TEST_TOKEN", GUILD, "12345678901234568", "herry", false, 60_000, 32,
                new DiscordMemoryStore.BackupPolicy(false, 600_000, 24, 7, 128L * 1024 * 1024));
    }
    private byte[] frame() {
        byte[] pcm = new byte[PcmAudio.FRAME_BYTES];
        for (int i = 0; i < pcm.length; i += 2) { pcm[i] = 0x2e; pcm[i + 1] = (byte) 0xe0; }
        return pcm;
    }
    private void speak(JdaAudioAdapter audio, DiscordSession session, AtomicLong now) throws Exception {
        for (int i = 0; i < 5; i++) { audio.receive(USER, frame()); session.tick().get(3, TimeUnit.SECONDS); now.addAndGet(20); }
        now.addAndGet(500); session.tick().get(3, TimeUnit.SECONDS);
    }
    @Test void manualBackupUsesIdentifiersAndCannotRestorePathsOrUnknownFiles() throws Exception {
        try (var store = new DiscordMemoryStore(directory, settings().backup(), () -> 1000)) {
            store.remember(NAME, "민수", Evidence.EXPLICIT, "confirmed", 0).get(3, TimeUnit.SECONDS);
            String id = store.backup().get(3, TimeUnit.SECONDS).getFileName().toString();
            assertTrue(store.backupIds().get(3, TimeUnit.SECONDS).contains(id));
            for (String invalid : new String[]{null, "../" + id, "C:\\data\\" + id, "data/current.mem", "periodic-1-9223372036854775808.mem"})
                assertThrows(IllegalArgumentException.class, () -> store.restoreBackup(invalid));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> store.restoreBackup("periodic-1-1.mem").get(3, TimeUnit.SECONDS));
            assertEquals("민수", store.snapshot().get(3, TimeUnit.SECONDS).facts().get(NAME).value());
            Files.createDirectory(directory.resolve("backups/periodic-9999-9999.mem"));
            assertFalse(store.backupIds().get(3, TimeUnit.SECONDS).contains("periodic-9999-9999.mem"));
        }
    }
    @Test void restorePausesPendingAudioAndRejectsResumeAndPersonalWritesUntilFinished() throws Exception {
        var now = new AtomicLong(1000); var block = new AtomicBoolean();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var store = new DiscordMemoryStore(directory, settings().backup(), () -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test memory gate timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
            return now.get();
        });
        store.remember(NAME, "민수", Evidence.EXPLICIT, "confirmed", 0).get(3, TimeUnit.SECONDS);
        String id = store.backup().get(3, TimeUnit.SECONDS).getFileName().toString();
        var requests = new LinkedBlockingQueue<ResponsePipeline.Request>(); var recognized = new AtomicInteger();
        try (var session = new DiscordSession(settings(), store,
                (pcm, language) -> new SpeechRecognitionWorker.Recognition(recognized.incrementAndGet() == 1 ? "해리야 옛 이야기" : "해리야 새 이야기", false),
                request -> { requests.add(request); return "반가워요."; }, text -> new byte[PcmAudio.FRAME_BYTES], code -> fail(code), now::get, now::get, false);
             var audio = new JdaAudioAdapter(session, 0.01, code -> fail(code))) {
            audio.connected(true); audio.participants(Set.of(USER), Map.of()).get(3, TimeUnit.SECONDS);
            speak(audio, session, now); assertNotNull(requests.poll(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!audio.canProvide() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(audio.canProvide());
            block.set(true);
            var writing = store.remember(STYLE, "REFUSED", Evidence.EXPLICIT, "later", 0);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var restoring = audio.restoreBackup(id); session.tick().get(3, TimeUnit.SECONDS);
            assertFalse(audio.canReceiveUser()); assertFalse(audio.canProvide()); assertNull(audio.provide20MsAudio());
            assertThrows(java.util.concurrent.ExecutionException.class, () -> audio.listening(true).get(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> session.confirmedName(USER, "지수", "during").get(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> session.forget(USER).get(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> session.backup().get(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> audio.restoreBackup(id).get(3, TimeUnit.SECONDS));
            release.countDown(); writing.get(3, TimeUnit.SECONDS); restoring.get(3, TimeUnit.SECONDS); block.set(false);
            assertFalse(audio.canReceiveUser());
            audio.connected(false); audio.connected(true); audio.participants(Set.of(USER), Map.of()).get(3, TimeUnit.SECONDS);
            assertFalse(audio.canReceiveUser()); audio.listening(true).get(3, TimeUnit.SECONDS);
            speak(audio, session, now); var fresh = requests.poll(3, TimeUnit.SECONDS); assertNotNull(fresh);
            assertEquals(List.of("해리야 새 이야기"), fresh.context().stream().map(ConversationTurns.Line::text).toList());
            assertEquals(List.of("민수"), fresh.memory().stream().map(Fact::value).toList());
        } finally { release.countDown(); store.close(); }
    }
    @Test void failedRestoreKeepsCurrentMemoryAndRequiresExplicitListeningResume() throws Exception {
        var now = new AtomicLong(1000);
        var store = new DiscordMemoryStore(directory, settings().backup(), now::get);
        store.remember(NAME, "민수", Evidence.EXPLICIT, "confirmed", 0).get(3, TimeUnit.SECONDS);
        String id = store.backup().get(3, TimeUnit.SECONDS).getFileName().toString();
        Files.write(directory.resolve("backups").resolve(id), new byte[]{1, 2, 3});
        try (var session = new DiscordSession(settings(), store, (pcm, language) -> new SpeechRecognitionWorker.Recognition("", false),
                request -> fail("unexpected dialogue"), text -> fail("unexpected speech"), code -> fail(code), now::get, now::get, false);
             var audio = new JdaAudioAdapter(session, 0.01, code -> fail(code))) {
            audio.connected(true); audio.participants(Set.of(USER), Map.of()).get(3, TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> audio.restoreBackup(id).get(3, TimeUnit.SECONDS));
            assertFalse(audio.canReceiveUser());
            assertEquals("민수", store.visible(PERSON, Set.of(USER)).get(3, TimeUnit.SECONDS).getFirst().value());
            audio.listening(true).get(3, TimeUnit.SECONDS); assertTrue(audio.canReceiveUser());
        }
    }
    @Test void manualRestoreStillHonorsDeletionAfterRestart() throws Exception {
        String id;
        try (var store = new DiscordMemoryStore(directory, settings().backup(), () -> 1000)) {
            store.remember(NAME, "민수", Evidence.EXPLICIT, "confirmed", 0).join();
            id = store.backup().join().getFileName().toString(); store.forget(PERSON).join();
            store.restoreBackup(id).join(); assertTrue(store.snapshot().join().facts().isEmpty());
        }
        try (var store = new DiscordMemoryStore(directory, settings().backup(), () -> 1000)) {
            store.restoreBackup(id).join(); assertTrue(store.visible(PERSON, Set.of(USER)).join().isEmpty());
        }
    }
    @Test void symlinkedRecoveryPointCannotImportAFileOutsideBackups() throws Exception {
        try (var store = new DiscordMemoryStore(directory, settings().backup(), () -> 1000)) {
            store.remember(NAME, "민수", Evidence.EXPLICIT, "confirmed", 0).join();
            Path backup = store.backup().join();
            Path external = directory.resolve("external.mem"); Files.copy(backup, external);
            Path link = directory.resolve("backups/periodic-2000-999.mem");
            try { Files.createSymbolicLink(link, external); }
            catch (java.nio.file.FileSystemException | UnsupportedOperationException unavailable) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Host does not support this test's symbolic links");
            }
            assertFalse(store.backupIds().join().contains(link.getFileName().toString()));
            assertThrows(java.util.concurrent.CompletionException.class, () -> store.restoreBackup(link.getFileName().toString()).join());
            assertThrows(java.io.IOException.class, () -> MemoryFiles.read(link));
        }
    }
}
