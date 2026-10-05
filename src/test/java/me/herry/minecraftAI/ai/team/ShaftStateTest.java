package me.herry.minecraftAI.ai.team;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 굴 기록을 저장하고 되살리는 규칙. 서버를 재시작해도 돌아갈 길과 발판 보호가 남아야 한다.
 */
class ShaftStateTest {
    private static final UUID WORLD = UUID.randomUUID();

    private static void digStair(ShaftRegistry registry, String owner, int x, int y, int steps) {
        for (int i = 0; i <= steps; i++) registry.record(owner, WORLD, new BlockPoint(x + i, y - i, 0));
    }

    @Test
    void savedShaftsComeBackWithTheSameSteps() {
        ShaftRegistry before = new ShaftRegistry();
        digStair(before, "miner", 0, 70, 30);
        digStair(before, "miner", 100, 70, 5);
        Map<String, Object> state = before.exportState("miner");

        ShaftRegistry after = new ShaftRegistry();
        after.importState("miner", state);
        // 걸어 올라갈 굴로 다시 찾을 수 있다.
        ShaftRegistry.Shaft up = after.higher(WORLD, new BlockPoint(30, 40, 0), 3.0, 4);
        assertNotNull(up);
        assertEquals(new BlockPoint(0, 70, 0), up.entrance());
        assertEquals(new BlockPoint(30, 40, 0), up.end());
        // 발판도 다시 보호된다.
        assertTrue(after.isStep(WORLD, new BlockPoint(12, 58, 0)));
        // 가장 최근에 판 굴이 무엇이었는지도 그대로다.
        ShaftRegistry.Shaft latest = after.latestOf("miner", WORLD);
        assertNotNull(latest);
        assertEquals(new BlockPoint(100, 70, 0), latest.entrance());
    }

    // 저장은 AI 별이다. 다른 AI 의 굴은 섞이지 않고, 되살릴 때 다른 AI 의 기록을 건드리지 않는다.
    @Test
    void stateIsPerOwner() {
        ShaftRegistry registry = new ShaftRegistry();
        digStair(registry, "miner", 0, 70, 10);
        digStair(registry, "other", 50, 70, 10);
        Map<String, Object> state = registry.exportState("miner");
        assertEquals(1, ((List<?>) state.get("shafts")).size());

        ShaftRegistry restored = new ShaftRegistry();
        digStair(restored, "other", 50, 70, 10);
        restored.importState("miner", state);
        assertTrue(restored.isStep(WORLD, new BlockPoint(5, 65, 0)));
        assertTrue(restored.isStep(WORLD, new BlockPoint(55, 65, 0)));
    }

    // 한 칸짜리 기록은 굴이 아니라서 저장하지 않는다. 굴이 없으면 빈 목록이다.
    @Test
    void emptyAndSinglePointShaftsAreNotSaved() {
        ShaftRegistry registry = new ShaftRegistry();
        assertEquals(List.of(), registry.exportState("miner").get("shafts"));
        registry.record("miner", WORLD, new BlockPoint(0, 70, 0));
        assertEquals(List.of(), registry.exportState("miner").get("shafts"));
    }

    // 망가진 줄은 그 굴만 잃는다. 나머지는 되살아난다.
    @Test
    void brokenLinesAreSkipped() {
        ShaftRegistry registry = new ShaftRegistry();
        registry.importState("miner", Map.of("shafts", List.of(
                "not-a-world|0,70,0;1,69,0",
                WORLD + "|0,70,0;oops",
                WORLD + "|5,70,0;6,69,0;7,68,0",
                "no separator")));
        assertTrue(registry.isStep(WORLD, new BlockPoint(6, 69, 0)));
        assertFalse(registry.isStep(WORLD, new BlockPoint(1, 69, 0)));
        // 굴 기록이 없는 저장 파일(예전 판)은 지금 기록을 그대로 둔다.
        registry.importState("miner", Map.of());
        assertTrue(registry.isStep(WORLD, new BlockPoint(6, 69, 0)));
    }

    // 막다른 굴이라는 표시는 저장하지 않는다. 되살아난 뒤에는 다시 쓸 수 있는 굴로 본다.
    @Test
    void deadEndMarksAreNotSaved() {
        ShaftRegistry before = new ShaftRegistry();
        digStair(before, "miner", 0, 70, 30);
        BlockPoint end = new BlockPoint(30, 40, 0);
        before.noteBlockedEnd(WORLD, end, 2.0, 1000L);
        before.noteBlockedEnd(WORLD, end, 2.0, 2000L);
        assertNull(before.deeper(WORLD, new BlockPoint(3, 70, 2), 24.0, 4));

        ShaftRegistry after = new ShaftRegistry();
        after.importState("miner", before.exportState("miner"));
        assertNotNull(after.deeper(WORLD, new BlockPoint(3, 70, 2), 24.0, 4));
    }
}
