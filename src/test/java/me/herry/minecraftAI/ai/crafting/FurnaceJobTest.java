package me.herry.minecraftAI.ai.crafting;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnaceJobTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final BlockPoint POS = new BlockPoint(1, 64, -2);

    @Test
    void expectsTenSecondsPerItem() {
        FurnaceJob job = FurnaceJob.start(WORLD, POS, true, 8, 1000L);
        assertEquals(1000L + 8 * 200L, job.readyAt());
        // 다 구워졌어야 할 시각을 넘겨도 조금은 더 기다려 본다.
        assertFalse(job.isOverdue(2600L));
        assertFalse(job.isOverdue(2799L));
        assertTrue(job.isOverdue(2800L));
    }

    // 회귀: 몬스터를 피해 숨은 직후라 그 자리에서는 굴을 낼 수 없었는데, 한 번 막혔다고 바로 화로를 포기해서
    // 넣어 둔 철 3개와 화로를 두고 갔다. 2분 동안 계속 막혀 있을 때만 포기한다.
    @Test
    void doesNotGiveUpTheFurnaceTheFirstTimeTheWayIsBlocked() {
        assertFalse(FurnaceJob.shouldGiveUp(-1L, 5000L));
        assertFalse(FurnaceJob.shouldGiveUp(5000L, 5000L));
        assertFalse(FurnaceJob.shouldGiveUp(5000L, 5000L + FurnaceJob.GIVE_UP_TICKS - 1));
        assertTrue(FurnaceJob.shouldGiveUp(5000L, 5000L + FurnaceJob.GIVE_UP_TICKS));
        // 틱 0 에 막힌 것도 막힌 것이다.
        assertTrue(FurnaceJob.shouldGiveUp(0L, FurnaceJob.GIVE_UP_TICKS));
    }

    @Test
    void knowsWhichFurnaceItIs() {
        FurnaceJob job = FurnaceJob.start(WORLD, POS, false, 3, 0L);
        assertTrue(job.isAt(WORLD, new BlockPoint(1, 64, -2)));
        assertFalse(job.isAt(WORLD, new BlockPoint(1, 65, -2)));
        assertFalse(job.isAt(OTHER_WORLD, POS));
    }

    @Test
    void survivesSaveAndLoad() {
        FurnaceJob job = FurnaceJob.start(WORLD, POS, true, 8, 1000L);
        assertEquals(job, FurnaceJob.importState(job.exportState()));

        // 저장 파일을 거치면 숫자가 다른 형으로 돌아올 수 있다.
        Map<String, Object> state = new LinkedHashMap<>(job.exportState());
        state.put("count", 8.0);
        state.put("readyAt", "2600");
        assertEquals(job, FurnaceJob.importState(state));
    }

    @Test
    void ignoresStateItCannotRead() {
        assertNull(FurnaceJob.importState(Map.of()));
        assertNull(FurnaceJob.importState(Map.of("world", "not-a-uuid", "pos", "1,64,-2", "food", true, "count", 8, "readyAt", 2600L)));
        assertNull(FurnaceJob.importState(Map.of("world", WORLD.toString(), "pos", "nowhere", "food", true, "count", 8, "readyAt", 2600L)));
    }
}
