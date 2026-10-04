package me.herry.minecraftAI.ai.memory;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySystemTest {
    private static final UUID OVERWORLD = UUID.randomUUID();
    private static final UUID NETHER = UUID.randomUUID();
    private static final BlockPoint ORIGIN = new BlockPoint(0, 64, 0);

    private final MemorySystem memory = new MemorySystem();

    @Test
    void findsNearestEntry() {
        memory.rememberPermanent(MemoryType.TREE, OVERWORLD, new BlockPoint(20, 64, 0), 0L);
        memory.rememberPermanent(MemoryType.TREE, OVERWORLD, new BlockPoint(5, 64, 0), 0L);

        assertEquals(new BlockPoint(5, 64, 0), memory.nearest(MemoryType.TREE, OVERWORLD, ORIGIN, 0L).orElseThrow().pos());
    }

    @Test
    void entriesExpire() {
        memory.remember(MemoryType.DANGER_PLACE, OVERWORLD, ORIGIN, 100L, 50L);

        assertTrue(memory.contains(MemoryType.DANGER_PLACE, OVERWORLD, ORIGIN, 149L));
        assertFalse(memory.contains(MemoryType.DANGER_PLACE, OVERWORLD, ORIGIN, 150L));
        assertTrue(memory.nearest(MemoryType.DANGER_PLACE, OVERWORLD, ORIGIN, 150L).isEmpty());

        memory.prune(150L);
        assertEquals(0, memory.size());
    }

    @Test
    void permanentEntriesDoNotExpire() {
        // 거점(HOME)은 WorldModel 의 Base 로 옮겨졌다. 만료되지 않는 기억은 침대로 확인한다.
        memory.rememberPermanent(MemoryType.BED, OVERWORLD, ORIGIN, 0L);
        memory.prune(Long.MAX_VALUE / 2);

        assertTrue(memory.contains(MemoryType.BED, OVERWORLD, ORIGIN, Long.MAX_VALUE / 2));
    }

    @Test
    void worldsAreKeptSeparate() {
        // 같은 좌표라도 다른 월드의 기억이 섞이면 안 된다.
        memory.rememberPermanent(MemoryType.WORKBENCH, NETHER, ORIGIN, 0L);

        assertTrue(memory.nearest(MemoryType.WORKBENCH, OVERWORLD, ORIGIN, 0L).isEmpty());
        assertEquals(0, memory.count(MemoryType.WORKBENCH, OVERWORLD, 0L));
        assertEquals(1, memory.count(MemoryType.WORKBENCH, NETHER, 0L));
    }

    @Test
    void rememberingSamePlaceTwiceKeepsOneEntry() {
        memory.remember(MemoryType.TREE, OVERWORLD, ORIGIN, 0L, 10L);
        memory.remember(MemoryType.TREE, OVERWORLD, ORIGIN, 5L, 100L);

        assertEquals(1, memory.count(MemoryType.TREE, OVERWORLD, 50L));
    }

    @Test
    void forgetRemovesEntry() {
        memory.rememberPermanent(MemoryType.TREE, OVERWORLD, ORIGIN, 0L);
        memory.forget(MemoryType.TREE, OVERWORLD, ORIGIN);

        assertEquals(0, memory.count(MemoryType.TREE, OVERWORLD, 0L));
    }

    @Test
    void entryCountIsCapped() {
        for (int i = 0; i < 200; i++) memory.rememberPermanent(MemoryType.STONE, OVERWORLD, new BlockPoint(i, 64, 0), i);

        assertEquals(64, memory.count(MemoryType.STONE, OVERWORLD, 0L));
        // 오래된 것부터 버려지므로 가장 최근 것은 남아 있어야 한다.
        assertTrue(memory.contains(MemoryType.STONE, OVERWORLD, new BlockPoint(199, 64, 0), 0L));
        assertFalse(memory.contains(MemoryType.STONE, OVERWORLD, new BlockPoint(0, 64, 0), 0L));
    }

    @Test
    void failuresAndRecentPathAreBounded() {
        for (int i = 0; i < 100; i++) {
            memory.recordFailure("MoveTo", "stuck", i);
            memory.recordVisit(OVERWORLD, new BlockPoint(i, 64, 0));
        }

        assertEquals(20, memory.getFailures().size());
        assertEquals(32, memory.getRecentPath().size());
    }

    @Test
    void standingStillDoesNotFillRecentPath() {
        for (int i = 0; i < 10; i++) memory.recordVisit(OVERWORLD, ORIGIN);

        assertEquals(1, memory.getRecentPath().size());
    }

    // 같은 곳에서 거듭 길을 찾지 못하면 점점 더 오래 피한다. 끝없이 같은 곳으로 다시 가지 않게 하기 위해서다.
    @Test
    void unreachablePlacesAreAvoidedLongerEachTime() {
        assertEquals(1200L, memory.rememberUnreachable(OVERWORLD, ORIGIN, 0L));
        assertEquals(2400L, memory.rememberUnreachable(OVERWORLD, ORIGIN, 1200L));
        assertEquals(4800L, memory.rememberUnreachable(OVERWORLD, ORIGIN, 3600L));
        assertTrue(memory.contains(MemoryType.UNREACHABLE, OVERWORLD, ORIGIN, 8000L));

        // 한도까지만 늘어난다.
        for (int i = 0; i < 10; i++) memory.rememberUnreachable(OVERWORLD, ORIGIN, 9000L);
        assertEquals(19200L, memory.rememberUnreachable(OVERWORLD, ORIGIN, 9000L));

        // 다른 곳은 처음부터 센다.
        assertEquals(1200L, memory.rememberUnreachable(OVERWORLD, new BlockPoint(50, 64, 0), 0L));
    }

    @Test
    void arrivingClearsTheUnreachableHistory() {
        memory.rememberUnreachable(OVERWORLD, ORIGIN, 0L);
        memory.rememberUnreachable(OVERWORLD, ORIGIN, 0L);
        memory.clearUnreachable(OVERWORLD, ORIGIN);

        assertFalse(memory.contains(MemoryType.UNREACHABLE, OVERWORLD, ORIGIN, 1L));
        assertEquals(1200L, memory.rememberUnreachable(OVERWORLD, ORIGIN, 0L));
    }

    // 서버를 재시작해도 중요한 기억(광석, 시설, 위험한 곳)은 남고, 금방 다시 찾을 수 있는 것(나무, 돌)은 저장하지 않는다.
    @Test
    void importantMemoriesSurviveSaveAndRestore() {
        memory.remember(MemoryType.DIAMOND_ORE, OVERWORLD, new BlockPoint(10, -50, 10), 100L, 6000L);
        memory.rememberPermanent(MemoryType.FURNACE, OVERWORLD, new BlockPoint(1, 64, 1), 100L);
        memory.remember(MemoryType.DANGER_PLACE, NETHER, new BlockPoint(3, 40, 3), 100L, 12000L);
        memory.remember(MemoryType.TREE, OVERWORLD, new BlockPoint(5, 64, 5), 100L, 6000L);
        memory.rememberUnreachable(OVERWORLD, new BlockPoint(9, 64, 9), 100L);
        memory.remember(MemoryType.IRON_ORE, OVERWORLD, new BlockPoint(7, 20, 7), 100L, 50L);
        memory.setLastGoal("MINE_IRON");

        MemorySystem restored = new MemorySystem();
        // 저장하는 시점(200)에 이미 만료된 철 광석 기억은 저장되지 않는다.
        restored.importState(memory.exportState(200L));

        assertTrue(restored.contains(MemoryType.DIAMOND_ORE, OVERWORLD, new BlockPoint(10, -50, 10), 200L));
        assertTrue(restored.contains(MemoryType.FURNACE, OVERWORLD, new BlockPoint(1, 64, 1), 200L));
        assertTrue(restored.contains(MemoryType.DANGER_PLACE, NETHER, new BlockPoint(3, 40, 3), 200L));
        assertEquals(0, restored.count(MemoryType.TREE, OVERWORLD, 200L));
        assertEquals(0, restored.count(MemoryType.UNREACHABLE, OVERWORLD, 200L));
        assertEquals(0, restored.count(MemoryType.IRON_ORE, OVERWORLD, 200L));
        assertEquals("MINE_IRON", restored.getLastGoal());
        // 만료 시각도 그대로 따라온다.
        assertFalse(restored.contains(MemoryType.DIAMOND_ORE, OVERWORLD, new BlockPoint(10, -50, 10), 6100L));
        assertTrue(restored.contains(MemoryType.FURNACE, OVERWORLD, new BlockPoint(1, 64, 1), 1_000_000L));
    }

    @Test
    void brokenSavedLinesAreSkipped() {
        MemorySystem restored = new MemorySystem();
        restored.importState(java.util.Map.of("entries", java.util.List.of(
                "FURNACE|" + OVERWORLD + "|1,64,1|0|-1",
                "NO_SUCH_TYPE|" + OVERWORLD + "|1,64,1|0|-1",
                "FURNACE|not-a-uuid|1,64,1|0|-1",
                "FURNACE|" + OVERWORLD + "|1,64|0|-1",
                "too|short")));

        assertEquals(1, restored.size());
    }

    @Test
    void tracksRecentAttack() {
        assertFalse(memory.wasAttackedWithin(100L, 60L));

        memory.recordAttack(OVERWORLD, ORIGIN, UUID.randomUUID(), 100L);

        assertTrue(memory.wasAttackedWithin(150L, 60L));
        assertFalse(memory.wasAttackedWithin(200L, 60L));
    }
}
