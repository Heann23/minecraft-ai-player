package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static me.herry.minecraftAI.discord.DiscordMemory.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscordMemoryStoreTest {
    @TempDir Path directory;
    private long now = 1_000_000;
    private static final Subject A = new Subject("guild", "herry", "A");
    private static final Subject B = new Subject("guild", "herry", "B");
    private static final Key NAME = new Key(A, Kind.NAME, "", "preferred");
    private static final Key STYLE = new Key(A, Kind.SPEECH_AGREEMENT, "", "casual");
    private static final Key RELATION = new Key(A, Kind.RELATION, "B", "relationship");
    private DiscordMemoryStore open() throws Exception {
        return new DiscordMemoryStore(directory, new DiscordMemoryStore.BackupPolicy(false, 600_000, 2, 2, 128L * 1024 * 1024), () -> now);
    }
    private void name(DiscordMemoryStore store, String name) { store.remember(NAME, name, Evidence.EXPLICIT, "utterance-1", 0).join(); }

    @Test void restartPreservesConfirmedNameAndRefusal() throws Exception {
        try (var store = open()) { name(store, "민수"); store.remember(STYLE, "REFUSED", Evidence.EXPLICIT, "utterance-2", 0).join(); }
        try (var store = open()) {
            assertEquals("민수", store.snapshot().join().facts().get(NAME).value());
            assertEquals("REFUSED", store.snapshot().join().facts().get(STYLE).value());
        }
    }
    @Test void userCharacterAndGuildMemoriesAreSeparated() throws Exception {
        try (var store = open()) {
            name(store, "민수");
            store.remember(new Key(B, Kind.NAME, "", "preferred"), "수빈", Evidence.EXPLICIT, "2", 0).join();
            assertTrue(store.visible(new Subject("other", "herry", "A"), Set.of("A")).join().isEmpty());
            assertTrue(store.visible(new Subject("guild", "other", "A"), Set.of("A")).join().isEmpty());
            assertEquals("민수", store.visible(A, Set.of("A", "B")).join().getFirst().value());
            assertEquals("수빈", store.visible(B, Set.of("A", "B")).join().getFirst().value());
        }
    }
    @Test void relationVisibilityRequiresBothParticipantsAndKeepsDirection() throws Exception {
        try (var store = open()) {
            store.remember(RELATION, "직접 알려 준 친구", Evidence.EXPLICIT, "1", 0).join();
            assertTrue(store.visible(A, Set.of("A")).join().isEmpty());
            assertEquals(1, store.visible(A, Set.of("A", "B")).join().size());
            assertTrue(store.visible(B, Set.of("A", "B")).join().isEmpty());
        }
    }
    @Test void observedStyleExpiresAndCannotConfirmRealRelationOrConsent() throws Exception {
        try (var store = open()) {
            var habit = new Key(A, Kind.ADDRESS_STYLE, "B", "speech");
            store.remember(habit, "casual", Evidence.OBSERVED, "1", now + 100).join();
            now += 100;
            assertTrue(store.visible(A, Set.of("A", "B")).join().isEmpty());
            assertThrows(CompletionException.class, () -> store.remember(RELATION, "친구", Evidence.OBSERVED, "2", now + 100).join());
            assertThrows(CompletionException.class, () -> store.remember(STYLE, "ALLOWED", Evidence.OBSERVED, "3", now + 100).join());
        }
    }
    @Test void deletionSurvivesManualRestoreAndRestart() throws Exception {
        Path backup;
        try (var store = open()) { name(store, "민수"); backup = store.backup().join(); store.forget(A).join(); store.restore(backup).join(); assertTrue(store.snapshot().join().facts().isEmpty()); }
        try (var store = open()) { assertTrue(store.snapshot().join().facts().isEmpty()); }
    }
    @Test void automaticRecoveryReportsSkippedCorruptAndFutureBackupsWithoutPersonalContents() throws Exception {
        long savedAt;
        try (var store = open()) { name(store, "민수"); savedAt = store.snapshot().join().createdAt(); store.backup().join(); }
        Files.write(directory.resolve("backups/periodic-2000-99.mem"), new byte[]{1, 2, 3});
        MemoryFiles.write(directory.resolve("backups/periodic-2000-98.mem"), Snapshot.empty(now + 1));
        Files.write(directory.resolve("data/current.mem"), new byte[]{4, 5, 6});
        try (var recovered = open()) {
            assertTrue(recovered.status().recovered()); assertEquals(savedAt, recovered.status().recoveredAt());
            assertEquals(2, recovered.status().rejectedRecoveryPoints());
            assertFalse(recovered.status().toString().contains("민수"));
            assertEquals("민수", recovered.visible(A, Set.of("A")).join().getFirst().value());
            assertFalse(recovered.hasFailure());
        }
    }
    @Test void forgettingEitherRelationParticipantDeletesRelation() throws Exception {
        try (var store = open()) {
            store.remember(RELATION, "친구", Evidence.EXPLICIT, "1", 0).join(); Path backup = store.backup().join();
            store.forget(B).join(); store.restore(backup).join(); assertFalse(store.snapshot().join().facts().containsKey(RELATION));
        }
    }
    @Test void keyDeletionAndCorrectionCannotReappearFromOldBackup() throws Exception {
        try (var store = open()) {
            name(store, "옛 이름"); Path old = store.backup().join(); name(store, "새 이름"); store.restore(old).join();
            assertFalse(store.snapshot().join().facts().containsKey(NAME));
            name(store, "확인한 이름"); Path next = store.backup().join(); store.forget(NAME).join(); store.restore(next).join();
            assertFalse(store.snapshot().join().facts().containsKey(NAME));
        }
    }
    @Test void newConfirmedMemoryAfterDeletionIsAllowed() throws Exception {
        try (var store = open()) { name(store, "옛 이름"); store.forget(A).join(); name(store, "다시 알려 준 이름"); }
        try (var store = open()) { assertEquals("다시 알려 준 이름", store.snapshot().join().facts().get(NAME).value()); }
    }
    @Test void corruptionRestoresBackupAndPreservesOriginalWithoutRevivingDeletes() throws Exception {
        try (var store = open()) { name(store, "지워야 할 이름"); store.backup().join(); store.forget(A).join(); }
        Files.writeString(directory.resolve("data/current.mem"), "corruption");
        try (var store = open()) { assertTrue(store.status().recovered()); assertTrue(store.snapshot().join().facts().isEmpty()); }
        try (var paths = Files.list(directory.resolve("data"))) { assertTrue(paths.anyMatch(p -> p.getFileName().toString().startsWith("corrupt-"))); }
    }
    @Test void missingCurrentRecoversBackup() throws Exception {
        try (var store = open()) { name(store, "민수"); store.backup().join(); }
        Files.delete(directory.resolve("data/current.mem"));
        try (var store = open()) { assertTrue(store.status().recovered()); assertEquals("민수", store.snapshot().join().facts().get(NAME).value()); }
    }
    @Test void corruptDeletionJournalFailsClosed() throws Exception {
        try (var store = open()) { name(store, "민수"); store.backup().join(); store.forget(A).join(); }
        Files.writeString(directory.resolve("data/deletions.mem"), "corrupt");
        assertThrows(java.io.IOException.class, this::open);
    }
    @Test void missingDeletionJournalCannotReviveDeletedFactsFromBackup() throws Exception {
        try (var store = open()) { name(store, "민수"); store.backup().join(); store.forget(A).join(); }
        Files.delete(directory.resolve("data/deletions.mem")); Files.delete(directory.resolve("data/current.mem"));
        assertThrows(java.io.IOException.class, this::open);
    }
    @Test void backupChecksumFailureCannotReplaceGoodData() throws Exception {
        try (var store = open()) {
            name(store, "옛 이름"); Path backup = store.backup().join(); name(store, "정정한 이름");
            byte[] bytes = Files.readAllBytes(backup); bytes[bytes.length - 1] ^= 1; Files.write(backup, bytes);
            assertThrows(CompletionException.class, () -> store.restore(backup).join());
            assertEquals("정정한 이름", store.snapshot().join().facts().get(NAME).value());
        }
    }
    @Test void serialUpdatesAndConcurrentBackupProduceConsistentRevisions() throws Exception {
        try (var store = open()) {
            var tasks = new java.util.ArrayList<CompletableFuture<?>>();
            for (int i = 0; i < 20; i++) {
                tasks.add(store.remember(new Key(A, Kind.ITEM_STORY, "", "item" + i), "confirmed" + i, Evidence.EXPLICIT, "u" + i, 0));
                if (i % 5 == 0) tasks.add(store.backup());
            }
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
            assertEquals(20, store.snapshot().join().revision());
            for (Path backup : MemoryFiles.backups(directory.resolve("backups"))) {
                var snapshot = MemoryFiles.read(backup); assertEquals(snapshot.revision(), snapshot.facts().size());
            }
        }
    }
    @Test void retentionPrunesOnlyAfterValidBackupAndUnchangedBackupIsSkipped() throws Exception {
        try (var store = open()) {
            for (int i = 0; i < 5; i++) { name(store, "name" + i); store.backup().join(); now += 86_400_000; }
            assertNull(store.backup().join());
            var files = MemoryFiles.backups(directory.resolve("backups"));
            assertEquals(4, files.size()); for (Path file : files) assertNotNull(MemoryFiles.read(file));
        }
    }
    @Test void singleWriterLeaseAndCloseProtectFiles() throws Exception {
        var store = open(); assertThrows(java.io.IOException.class, this::open); name(store, "민수"); store.close(); store.close();
        assertThrows(CompletionException.class, () -> store.snapshot().join());
        try (var reopened = open()) { assertEquals(1, reopened.snapshot().join().revision()); }
    }
    @Test void failedDataWriteKeepsDeletionJournalAndPreviousBackupSafe() throws Exception {
        try (var store = open()) {
            name(store, "지워야 할 이름"); store.backup().join();
            Path current = directory.resolve("data/current.mem"); Files.delete(current); Files.createDirectory(current); Files.writeString(current.resolve("obstacle"), "test");
            assertThrows(CompletionException.class, () -> store.forget(A).join());
            assertTrue(store.snapshot().join().facts().isEmpty()); assertTrue(store.hasFailure());
            Files.delete(current.resolve("obstacle")); Files.delete(current);
        }
        try (var store = open()) { assertTrue(store.snapshot().join().facts().isEmpty()); }
    }
    @Test void codecRejectsUnsupportedSchemaAndHeaderCorruption() throws Exception {
        byte[] bytes = MemoryCodec.encode(Snapshot.empty(now)); bytes[39] ^= 1;
        assertThrows(java.io.IOException.class, () -> MemoryCodec.decode(bytes));
        // A valid checksum must not allow an unsupported schema either.
        byte[] checksum = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOfRange(bytes, 32, bytes.length));
        System.arraycopy(checksum, 0, bytes, 0, checksum.length);
        assertThrows(java.io.IOException.class, () -> MemoryCodec.decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(-1, now, Map.of(), Map.of(), Map.of()));
    }
    @Test void restoreCannotReadOutsideBackups() throws Exception {
        try (var store = open()) { assertThrows(CompletionException.class, () -> store.restore(directory.resolve("data/current.mem")).join()); }
    }
}
