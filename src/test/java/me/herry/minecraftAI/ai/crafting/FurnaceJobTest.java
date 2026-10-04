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
