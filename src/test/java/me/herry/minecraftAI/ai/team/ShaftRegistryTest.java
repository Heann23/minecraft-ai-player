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

    // 회귀: 계단 굴이 물이 찬 동굴 옆(y -10)에서 끝났고 거기서는 어느 쪽으로도 더 팔 수 없었다. 다른 데로 옮겨 가도
    // "이미 파 둔 굴"이라며 그 끝으로 되돌아오기를 되풀이해서, 다이아몬드를 찾는 일이 10분 넘게 제자리였다.
    @Test
    void doesNotGoBackDownAShaftThatEndsWhereNothingCanBeDug() {
        digStair("miner", 0, 70, 30);
        BlockPoint end = new BlockPoint(30, 40, 0);
        BlockPoint top = new BlockPoint(3, 70, 2);
        assertNotNull(registry.deeper(WORLD, top, 24.0, 4));

        // 한 번 막힌 것으로는 막다른 굴로 치지 않는다 (공중에 떠 있었거나 지나가는 몬스터 때문일 수 있다).
        assertTrue(registry.noteBlockedEnd(WORLD, end, 2.0, 1000L));
        // 굴의 끝이 아닌 곳에서는 적어 둘 굴이 없다. 한 칸만 기록된 굴도 굴로 치지 않는다.
        assertFalse(registry.noteBlockedEnd(WORLD, new BlockPoint(10, 60, 0), 2.0, 1000L));
        registry.record("other", WORLD, new BlockPoint(200, 70, 200));
        assertFalse(registry.noteBlockedEnd(WORLD, new BlockPoint(200, 70, 200), 2.0, 1000L));
        assertNull(registry.deadEndNear(WORLD, end, 2.0));
        // 곧바로 다시 확인한 것은 같은 일로 친다.
        registry.noteBlockedEnd(WORLD, end, 2.0, 1001L);
        registry.noteBlockedEnd(WORLD, end, 2.0, 1050L);
        assertNull(registry.deadEndNear(WORLD, end, 2.0));
        assertNotNull(registry.deeper(WORLD, top, 24.0, 4));

        // 간격을 두고 다시 봐도 팔 수 없으면 막다른 굴이다. 따라 내려갈 굴로 고르지 않는다.
        registry.noteBlockedEnd(WORLD, end, 2.0, 1000L + ShaftRegistry.BLOCKED_RECHECK_TICKS);
        ShaftRegistry.Shaft dead = registry.deadEndNear(WORLD, end, 2.0);
        assertNotNull(dead);
        assertEquals(end, dead.end());
        assertNull(registry.deeper(WORLD, top, 24.0, 4));
        // 그 굴의 칸은 여전히 굴의 칸이다 (발판을 캐면 안 되고, 올라갈 때는 쓴다).
        assertTrue(registry.isStep(WORLD, new BlockPoint(10, 60, 0)));
        assertTrue(registry.isDeadEndStep(WORLD, new BlockPoint(10, 60, 0)));
        assertFalse(registry.isDeadEndStep(WORLD, new BlockPoint(10, 60, 1)));
        assertNotNull(registry.higher(WORLD, new BlockPoint(29, 41, 0), 4.0, 4));
        // 끝에서 먼 자리에서 팔 수 없었던 것은 이 굴의 일이 아니다.
        assertNull(registry.deadEndNear(WORLD, new BlockPoint(10, 60, 0), 2.0));
    }

    // 막다른 굴에서 몇 단 되돌아가 옆으로 낸 굴은 새 굴이고, 막다른 굴이 아니다.
    @Test
    void aBranchDugFromADeadEndShaftIsUsable() {
        digStair("miner", 0, 70, 30);
        BlockPoint end = new BlockPoint(30, 40, 0);
        registry.noteBlockedEnd(WORLD, end, 2.0, 1000L);
        registry.noteBlockedEnd(WORLD, end, 2.0, 2000L);
        assertNull(registry.deeper(WORLD, new BlockPoint(3, 70, 2), 24.0, 4));

        // (24,46,0) 에서 남쪽으로 내려가는 새 계단
        for (int i = 0; i <= 12; i++) registry.record("miner", WORLD, new BlockPoint(24, 46 - i, i));
        ShaftRegistry.Shaft branch = registry.deeper(WORLD, new BlockPoint(24, 46, 0), 8.0, 4);
        assertNotNull(branch);
        assertEquals(new BlockPoint(24, 34, 12), branch.end());
        assertFalse(registry.isDeadEnd(branch));
        assertFalse(registry.isDeadEndStep(WORLD, new BlockPoint(24, 40, 6)));
    }

    // 막다른 굴이라도 끝에서 한 칸 더 파게 되면(물이 빠졌거나 몬스터가 떠난 뒤) 다시 쓸 수 있는 굴이다.
    @Test
    void aDeadEndShaftIsUsableAgainOnceItIsDugFurther() {
        digStair("miner", 0, 70, 30);
        BlockPoint end = new BlockPoint(30, 40, 0);
        registry.noteBlockedEnd(WORLD, end, 2.0, 1000L);
        registry.noteBlockedEnd(WORLD, end, 2.0, 2000L);
        assertNull(registry.deeper(WORLD, new BlockPoint(3, 70, 2), 24.0, 4));

        registry.record("miner", WORLD, new BlockPoint(31, 39, 0));
        ShaftRegistry.Shaft shaft = registry.deeper(WORLD, new BlockPoint(3, 70, 2), 24.0, 4);
        assertNotNull(shaft);
        assertEquals(new BlockPoint(31, 39, 0), shaft.end());
        assertFalse(registry.isDeadEndStep(WORLD, new BlockPoint(10, 60, 0)));
    }
}
