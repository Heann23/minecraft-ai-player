package me.herry.minecraftAI.ai.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerfTest {
    // 테스트에서 시간을 마음대로 흘려보내기 위한 시계
    private long now;

    @Test
    void profilerSumsSectionsWithinATick() {
        TickProfiler profiler = new TickProfiler(() -> now);

        long started = profiler.begin();
        now += 3_000;
        profiler.end(TickProfiler.Section.ACTION, started);
        started = profiler.begin();
        now += 2_000;
        profiler.end(TickProfiler.Section.ACTION, started);
        profiler.endTick();

        TickProfiler.Stats stats = profiler.stats(TickProfiler.Section.ACTION);
        assertEquals(5.0, stats.averageMicros(), 1e-9);
        assertEquals(5.0, stats.maxMicros(), 1e-9);
        assertEquals(1, stats.ticks());
        // 재지 않은 구간은 0 이다.
        assertEquals(0.0, profiler.stats(TickProfiler.Section.PLANNING).maxMicros(), 1e-9);
    }

    @Test
    void profilerKeepsRecentMaxAndSmoothedAverage() {
        TickProfiler profiler = new TickProfiler(() -> now);
        for (int tick = 0; tick < 200; tick++) {
            long started = profiler.begin();
            // 첫 틱이 유난히 오래 걸린 경우 (레시피를 처음 읽을 때처럼)
            now += tick == 0 ? 100_000 : 1_000;
            profiler.end(TickProfiler.Section.TOTAL, started);
            profiler.endTick();
        }

        TickProfiler.Stats stats = profiler.stats(TickProfiler.Section.TOTAL);
        assertEquals(100.0, stats.maxMicros(), 1e-9);
        // 한 번 튄 값은 최댓값에는 남지만, 평균에는 오래 남지 않는다.
        assertTrue(stats.averageMicros() < 2.0, "average was " + stats.averageMicros());
        assertTrue(stats.averageMicros() > 1.0);
    }

    // 처음 몇 틱 동안은 단순 평균이다.
    @Test
    void profilerAverageStartsAsAPlainMean() {
        TickProfiler profiler = new TickProfiler(() -> now);
        for (long nanos : new long[]{4_000, 2_000, 6_000}) {
            long started = profiler.begin();
            now += nanos;
            profiler.end(TickProfiler.Section.PLANNING, started);
            profiler.endTick();
        }

        assertEquals(4.0, profiler.stats(TickProfiler.Section.PLANNING).averageMicros(), 1e-9);
    }

    @Test
    void budgetRunsOutAndResetsEachTick() {
        WorkBudget budget = new WorkBudget(1_000, () -> now);
        budget.beginTick();
        assertFalse(budget.isExhausted());

        now += 999;
        assertFalse(budget.isExhausted());
        now += 1;
        assertTrue(budget.isExhausted());
        // 한번 다 쓴 틱에는 계속 다 쓴 상태다.
        assertTrue(budget.isExhausted());
        assertEquals(1, budget.getExhaustedTicks());

        budget.beginTick();
        assertFalse(budget.isExhausted());
        assertEquals(2, budget.getTicks());
        assertEquals(1, budget.getExhaustedTicks());
    }

    @Test
    void zeroBudgetMeansUnlimited() {
        WorkBudget budget = new WorkBudget(0, () -> now);
        budget.beginTick();
        now += 1_000_000_000L;

        assertFalse(budget.isExhausted());
        assertFalse(budget.isLimited());
        assertEquals(0, budget.getExhaustedTicks());
    }
}
