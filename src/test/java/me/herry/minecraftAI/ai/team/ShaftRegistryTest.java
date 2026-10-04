package me.herry.minecraftAI.ai.team;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaftRegistryTest {
    private static final UUID WORLD = UUID.randomUUID();

    private final ShaftRegistry registry = new ShaftRegistry();

    // (x, y, 0) 에서 시작해 동쪽으로 한 칸 갈 때마다 한 칸씩 내려가는 계단
    private void digStair(String owner, int x, int y, int steps) {
        for (int i = 0; i <= steps; i++) registry.record(owner, WORLD, new BlockPoint(x + i, y - i, 0));
    }

    @Test
    void teammateReusesExistingShaft() {
        digStair("miner", 0, 70, 30);
        ShaftRegistry.Shaft shaft = registry.deeper(WORLD, new BlockPoint(3, 70, 2), 24.0, 4);
        assertNotNull(shaft);
        assertEquals("miner", shaft.owner());
        assertEquals(new BlockPoint(30, 40, 0), shaft.end());
    }

    @Test
    void knowsWhichCellsAreStepsOfAShaft() {
        digStair("miner", 0, 70, 5);
        assertTrue(registry.isStep(WORLD, new BlockPoint(2, 68, 0)));
        // 계단 바로 옆 벽이나 발판 블록 자체는 발을 디디는 칸이 아니다.
        assertFalse(registry.isStep(WORLD, new BlockPoint(2, 68, 1)));
        assertFalse(registry.isStep(WORLD, new BlockPoint(2, 67, 0)));
        assertFalse(registry.isStep(UUID.randomUUID(), new BlockPoint(2, 68, 0)));
    }

    @Test
    void ignoresShaftsThatAreFarOrShallow() {
        digStair("miner", 0, 70, 30);
        assertNull(registry.deeper(WORLD, new BlockPoint(200, 70, 200), 24.0, 4));
        // 이미 굴 끝보다 깊이 있으면 그 굴로 내려갈 이유가 없다.
        assertNull(registry.deeper(WORLD, new BlockPoint(30, 38, 0), 24.0, 4));
        assertNull(registry.deeper(UUID.randomUUID(), new BlockPoint(3, 70, 2), 24.0, 4));
    }

    @Test
    void disconnectedDiggingStartsANewShaft() {
        digStair("miner", 0, 70, 10);
        digStair("miner", 100, 70, 5);
        ShaftRegistry.Shaft latest = registry.latestOf("miner", WORLD);
        assertNotNull(latest);
        assertEquals(new BlockPoint(100, 70, 0), latest.entrance());
        // 먼저 판 굴도 계속 쓸 수 있다.
        assertNotNull(registry.deeper(WORLD, new BlockPoint(1, 70, 0), 8.0, 4));
    }

    @Test
    void findsWayUpFromInsideShaft() {
        digStair("miner", 0, 70, 30);
        ShaftRegistry.Shaft up = registry.higher(WORLD, new BlockPoint(28, 42, 1), 8.0, 4);
        assertNotNull(up);
        assertEquals(28, up.nearestIndex(new BlockPoint(28, 42, 1)));

        registry.discard(up);
        assertNull(registry.higher(WORLD, new BlockPoint(28, 42, 1), 8.0, 4));
    }
}
