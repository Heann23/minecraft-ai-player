package me.herry.minecraftAI.ai.perf;

import java.util.function.LongSupplier;

/**
 * 서버 틱 하나에서 모든 AI 가 함께 쓰는 시간 예산.
 * 블록 검색이나 경로 탐색처럼 "다음 틱으로 미뤄도 되는 일"은 일을 조금 할 때마다 isExhausted() 를 확인하고,
 * 예산을 다 썼으면 남은 일을 다음 틱으로 넘긴다. 위험 회피나 전투처럼 미룰 수 없는 판단은 예산과 상관없이 실행된다.
 * 메인 스레드에서만 쓴다.
 */
public final class WorkBudget {
    private final long budgetNanos;
    private final LongSupplier clock;
    private long tickStartedAt;
    private boolean exhaustedThisTick;
    private long ticks;
    private long exhaustedTicks;

    /**
     * @param budgetNanos 틱당 예산. 0 이하면 제한하지 않는다.
     */
    public WorkBudget(long budgetNanos) {
        this(budgetNanos, System::nanoTime);
    }

    public WorkBudget(long budgetNanos, LongSupplier clock) {
        this.budgetNanos = budgetNanos;
        this.clock = clock;
        this.tickStartedAt = clock.getAsLong();
    }

    // 서버 틱마다 AI 들을 실행하기 직전에 한 번 호출한다.
    public void beginTick() {
        tickStartedAt = clock.getAsLong();
        exhaustedThisTick = false;
        ticks++;
    }

    public boolean isExhausted() {
        if (budgetNanos <= 0L) return false;
        if (exhaustedThisTick) return true;
        if (clock.getAsLong() - tickStartedAt < budgetNanos) return false;
        exhaustedThisTick = true;
        exhaustedTicks++;
        return true;
    }

    public boolean isLimited() {
        return budgetNanos > 0L;
    }

    public long getBudgetNanos() {
        return budgetNanos;
    }

    // 이번 틱에 지금까지 쓴 시간
    public long usedNanos() {
        return Math.max(0L, clock.getAsLong() - tickStartedAt);
    }

    public long getTicks() {
        return ticks;
    }

    // 예산을 다 써서 일을 다음 틱으로 넘긴 틱의 수. 이 값이 계속 늘면 예산이 너무 작거나 서버가 느린 것이다.
    public long getExhaustedTicks() {
        return exhaustedTicks;
    }
}
