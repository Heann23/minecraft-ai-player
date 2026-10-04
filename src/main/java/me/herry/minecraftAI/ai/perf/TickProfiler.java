package me.herry.minecraftAI.ai.perf;

import java.util.function.LongSupplier;

/**
 * AI 한 명이 틱마다 어디에 시간을 쓰는지 잰다. 구간별로 "최근 평균"과 "최근 최댓값"을 보관한다.
 * 한 틱 안에서 같은 구간이 여러 번 실행되면 합쳐서 그 틱의 값으로 친다. Bukkit 에 의존하지 않는다.
 */
public final class TickProfiler {
    public enum Section {
        PERCEPTION("인식"),
        BLOCK_SCAN("블록 검색"),
        DECISION("상황 요약+목표 선택"),
        PLANNING("계획"),
        ACTION("행동 실행"),
        // 행동 실행 안에 포함된 시간이다. 따로 보려고 한 번 더 잰다.
        PATHFINDING("경로 탐색(행동에 포함)"),
        TOTAL("전체");

        private final String label;

        Section(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param averageMicros 최근 평균 (지수 이동 평균, 대략 최근 100틱)
     * @param maxMicros     최근 1~2분 사이의 최댓값
     */
    public record Stats(double averageMicros, double maxMicros, long ticks) {
    }

    private static final double SMOOTHING = 0.02;
    // 최댓값은 이 틱 수마다 창을 넘긴다. 직전 창과 지금 창 중 큰 값을 보여 준다.
    private static final int MAX_WINDOW_TICKS = 1200;
    private static final int COUNT = Section.values().length;

    private final LongSupplier clock;
    private final long[] current = new long[COUNT];
    private final double[] average = new double[COUNT];
    private final long[] windowMax = new long[COUNT];
    private final long[] previousMax = new long[COUNT];
    private long ticks;

    public TickProfiler() {
        this(System::nanoTime);
    }

    public TickProfiler(LongSupplier clock) {
        this.clock = clock;
    }

    // 구간을 재기 시작한 시각. end() 에 그대로 넘긴다.
    public long begin() {
        return clock.getAsLong();
    }

    public void end(Section section, long startedAt) {
        current[section.ordinal()] += Math.max(0L, clock.getAsLong() - startedAt);
    }

    /**
     * 한 틱의 측정을 마감한다. 이번 틱에 쌓인 값을 평균과 최댓값에 반영하고 다음 틱을 위해 비운다.
     */
    public void endTick() {
        ticks++;
        // 처음 얼마 동안은 단순 평균으로 계산한다. 그러지 않으면 첫 틱의 값(대개 가장 큰 값)이 평균에 오래 남는다.
        double weight = Math.max(SMOOTHING, 1.0 / ticks);
        for (int i = 0; i < COUNT; i++) {
            long nanos = current[i];
            average[i] += (nanos - average[i]) * weight;
            if (nanos > windowMax[i]) windowMax[i] = nanos;
            current[i] = 0L;
        }
        if (ticks % MAX_WINDOW_TICKS == 0) {
            System.arraycopy(windowMax, 0, previousMax, 0, COUNT);
            java.util.Arrays.fill(windowMax, 0L);
        }
    }

    public Stats stats(Section section) {
        int i = section.ordinal();
        return new Stats(average[i] / 1000.0, Math.max(windowMax[i], previousMax[i]) / 1000.0, ticks);
    }

    public long getTicks() {
        return ticks;
    }
}
