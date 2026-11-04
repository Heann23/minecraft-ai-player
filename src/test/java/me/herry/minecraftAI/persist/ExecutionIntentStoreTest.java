package me.herry.minecraftAI.persist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Logical persistence fixtures only; no world/inventory writes or Paper server execution. */
class ExecutionIntentStoreTest {
    @TempDir Path root;

    @Test void aLateOlderStartCannotOverwriteANewerDurableStop() {
        UUID id = UUID.randomUUID();
        try (var store = new ExecutionIntentStore(root, ignored -> {})) {
            var old = store.prepare("Learner", id, true);
            var stopped = store.prepare("Learner", id, false);
            store.write(stopped);
            store.write(old);
            assertEquals(Optional.of(false), store.read("LEARNER", id));
            assertTrue(stopped.completion().isDone());
        }
    }

    @Test void recreatedNamesDoNotInheritOrOverwriteAnotherIncarnationsIntent() {
        UUID old = UUID.randomUUID(), replacement = UUID.randomUUID();
        try (var store = new ExecutionIntentStore(root, ignored -> {})) {
            var removed = store.prepare("Learner", old, false);
            var created = store.prepare("Learner", replacement, true);
            store.write(created); store.write(removed);
            assertEquals(Optional.of(true), store.read("Learner", replacement));
            assertEquals(Optional.empty(), store.read("Learner", old));
        }
    }

    @Test void closeFlushesTheLatestValueWithoutRewritingWorldOrPlayerSnapshots() throws Exception {
        UUID id = UUID.randomUUID();
        var store = new ExecutionIntentStore(root.resolve("intents"), ignored -> {});
        store.prepare("Learner", id, true);
        store.prepare("Learner", id, false);
        store.close();
        try (var restored = new ExecutionIntentStore(root.resolve("intents"), ignored -> {})) {
            assertEquals(Optional.of(false), restored.read("Learner", id));
        }
        assertFalse(Files.exists(root.resolve("players")));
    }

    @Test void corruptedIntentRestoresStoppedAndPreservesTheOriginal() throws Exception {
        UUID id = UUID.randomUUID();
        try (var store = new ExecutionIntentStore(root, ignored -> {})) {
            store.save("Learner", id, true).get(5, TimeUnit.SECONDS);
            Path file = root.resolve("learner.json");
            Files.writeString(file, "corrupt original");
            assertEquals(Optional.of(false), store.read("Learner", id));
            assertEquals("corrupt original", Files.readString(file));
        }
    }

    @Test void snapshotCodecKeepsIncarnationAndSupportsLegacyAbsentIds() {
        AISnapshot snapshot = new AISnapshot();
        snapshot.name = "Learner"; snapshot.executionId = UUID.randomUUID(); snapshot.running = true;
        var encoded = SnapshotCodec.toMap(snapshot);
        assertEquals(snapshot.executionId, SnapshotCodec.fromMap(encoded).executionId);
        encoded.remove("executionId");
        assertNull(SnapshotCodec.fromMap(encoded).executionId);
        encoded.put("executionId", "corrupt");
        assertNull(SnapshotCodec.fromMap(encoded));
    }
}
