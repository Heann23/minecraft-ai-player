package me.herry.minecraftAI.persist;

import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.world.WorldModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final Logger LOGGER = Logger.getLogger("PersistenceTest");

    private static AISnapshot sample() {
        AISnapshot snapshot = new AISnapshot();
        snapshot.name = "AI_Player";
        snapshot.skinValue = "value";
        snapshot.skinSignature = "signature";
        snapshot.world = WORLD;
        snapshot.x = 12.5;
        snapshot.y = 64.0;
        snapshot.z = -7.25;
        snapshot.yaw = 90.0F;
        snapshot.pitch = -10.0F;
        snapshot.running = true;
        snapshot.ticks = 123_456L;

        MemorySystem memory = new MemorySystem();
        memory.rememberPermanent(MemoryType.FURNACE, WORLD, new BlockPoint(1, 64, 1), 0L);
        snapshot.sections.put("memory", memory.exportState(0L));
        WorldModel model = new WorldModel();
        model.ensureHome(WORLD, new BlockPoint(10, 64, 10)).setSheltered(true);
        model.rememberContents(WORLD, new BlockPoint(9, 64, 10), Map.of("IRON_INGOT", 5));
        model.markExplored(WORLD, 1, 2);
        snapshot.sections.put("worldModel", model.exportState());
        return snapshot;
    }

    @Test
    void codecRoundTrip() {
        AISnapshot restored = SnapshotCodec.fromMap(SnapshotCodec.toMap(sample()));

        assertNotNull(restored);
        assertEquals("AI_Player", restored.name);
        assertEquals("value", restored.skinValue);
        assertEquals(WORLD, restored.world);
        assertEquals(12.5, restored.x);
        assertEquals(-7.25, restored.z);
        assertEquals(90.0F, restored.yaw);
        assertTrue(restored.running);
        assertEquals(123_456L, restored.ticks);
        assertEquals(2, restored.sections.size());
    }

    @Test
    void codecRejectsUnreadableData() {
        assertNull(SnapshotCodec.fromMap(Map.of("x", 1.0)));
        assertNull(SnapshotCodec.fromMap(Map.of("name", " ")));

        // 더 새로운 형식으로 저장된 파일은 잘못 읽느니 읽지 않는다.
        Map<String, Object> future = new HashMap<>(SnapshotCodec.toMap(sample()));
        future.put("version", AISnapshot.VERSION + 1);
        assertNull(SnapshotCodec.fromMap(future));
    }

    @Test
    void codecToleratesMissingOptionalFields() {
        AISnapshot restored = SnapshotCodec.fromMap(Map.of("name", "Bot"));

        assertNotNull(restored);
        assertNull(restored.world);
        assertNull(restored.skinValue);
        assertFalse(restored.running);
        assertTrue(restored.section("memory").isEmpty());
    }

    // 실제 파일에 썼다가 읽어도 기억과 월드 모델이 그대로 돌아오는지 확인한다.
    @Test
    void yamlStoreSavesAndLoads(@TempDir Path directory) {
        YamlStateStore store = new YamlStateStore(directory.toFile(), LOGGER);
        store.prepareSave(sample()).run();

        List<AISnapshot> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        AISnapshot snapshot = loaded.getFirst();
        assertEquals("AI_Player", snapshot.name);
        assertEquals(WORLD, snapshot.world);
        assertEquals(123_456L, snapshot.ticks);

        MemorySystem memory = new MemorySystem();
        memory.importState(snapshot.section("memory"));
        assertTrue(memory.contains(MemoryType.FURNACE, WORLD, new BlockPoint(1, 64, 1), 0L));

        WorldModel model = new WorldModel();
        model.importState(snapshot.section("worldModel"));
        assertNotNull(model.getHome());
        assertTrue(model.getHome().isSheltered());
        assertEquals(new BlockPoint(10, 64, 10), model.getHome().center());
        assertEquals(5, model.storedCount("IRON_INGOT"));
        assertTrue(model.isExplored(WORLD, 1, 2));
    }

    @Test
    void yamlStoreOverwritesAndDeletes(@TempDir Path directory) {
        YamlStateStore store = new YamlStateStore(directory.toFile(), LOGGER);
        AISnapshot snapshot = sample();
        store.prepareSave(snapshot).run();
        snapshot.ticks = 999L;
        store.prepareSave(snapshot).run();

        assertEquals(1, store.loadAll().size());
        assertEquals(999L, store.loadAll().getFirst().ticks);

        store.delete("AI_Player");
        assertTrue(store.loadAll().isEmpty());
        // 없는 것을 지워도 문제없다.
        store.delete("AI_Player");
    }

    // 디스크에 쓰는 일은 다른 스레드에서 하므로, 먼저 준비한 저장이 나중에 실행될 수 있다. 그래도 새 내용이 남아야 한다.
    @Test
    void lateWriteDoesNotOverwriteANewerSave(@TempDir Path directory) {
        YamlStateStore store = new YamlStateStore(directory.toFile(), LOGGER);
        AISnapshot snapshot = sample();
        snapshot.ticks = 100L;
        Runnable older = store.prepareSave(snapshot);
        snapshot.ticks = 200L;
        Runnable newer = store.prepareSave(snapshot);

        newer.run();
        older.run();

        assertEquals(200L, store.loadAll().getFirst().ticks);
    }

    // AI 를 제거한 뒤에, 그 전에 준비해 둔 저장이 실행되어 파일을 되살리면 재시작 때 AI 가 다시 나타난다.
    @Test
    void lateWriteDoesNotBringBackADeletedAI(@TempDir Path directory) {
        YamlStateStore store = new YamlStateStore(directory.toFile(), LOGGER);
        Runnable pending = store.prepareSave(sample());

        store.delete("AI_Player");
        pending.run();

        assertTrue(store.loadAll().isEmpty());
        // 지운 뒤에 새로 저장하는 것은 된다 (같은 이름으로 다시 만든 경우).
        store.prepareSave(sample()).run();
        assertEquals(1, store.loadAll().size());
    }

    @Test
    void yamlStoreSkipsBrokenFiles(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("broken.yml"), "name: [unclosed");
        Files.writeString(directory.resolve("noname.yml"), "x: 1.0");
        YamlStateStore store = new YamlStateStore(directory.toFile(), LOGGER);
        store.prepareSave(sample()).run();

        assertEquals(1, store.loadAll().size());
    }

    @Test
    void yamlStoreHandlesMissingDirectory(@TempDir Path directory) {
        YamlStateStore store = new YamlStateStore(new File(directory.toFile(), "does/not/exist"), LOGGER);
        assertTrue(store.loadAll().isEmpty());

        // 저장할 때 폴더를 만든다.
        store.prepareSave(sample()).run();
        assertEquals(1, store.loadAll().size());
    }
}
