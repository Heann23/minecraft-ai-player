package me.herry.minecraftAI.ai.brain;

import me.herry.minecraftAI.ai.goal.GoalType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 실패에서 회복하는 정책. 같은 목표가 연달아 실패하면 그 목표를 한동안 쉬게 하고(다른 목표로 전환),
 * 쉬고 돌아와서도 또 실패하면 쉬는 시간을 점점 늘린다. 한 번 성공하면 처음 상태로 돌아간다.
 * 같은 행동을 끝없이 되풀이하지 않게 하는 마지막 안전장치다. Bukkit 에 의존하지 않는다.
 */
public final class RecoveryTracker {
    // 같은 목표의 계획이 이만큼 연달아 실패하면 그 목표를 쉰다.
    public static final int MAX_PLAN_FAILURES = 3;
    public static final long BASE_REST_TICKS = 200L;
    public static final long MAX_REST_TICKS = 2400L;

    private final Map<GoalType, Integer> streaks = new EnumMap<>(GoalType.class);
    // 목표별로, 성공 없이 쉬게 된 횟수
    private final Map<GoalType, Integer> rests = new EnumMap<>(GoalType.class);

    /**
     * 계획이 실패했음을 알린다.
     *
     * @return 이 목표를 쉬게 해야 하면 쉴 시간(틱), 아직 더 시도해도 되면 0
     */
    public long onPlanFailed(GoalType goal) {
        int streak = streaks.merge(goal, 1, Integer::sum);
        if (streak < MAX_PLAN_FAILURES) return 0L;

        streaks.remove(goal);
        int rest = rests.merge(goal, 1, Integer::sum);
        // 200, 400, 800, 1600, 2400, 2400, ...
        long ticks = BASE_REST_TICKS << Math.min(rest - 1, 10);
        return Math.min(MAX_REST_TICKS, ticks);
    }

    public void onPlanSucceeded(GoalType goal) {
        streaks.remove(goal);
        rests.remove(goal);
    }

    // 다른 목표로 바뀔 때 호출한다. 연속 실패 횟수만 지우고, 쉰 이력은 남겨서 곧 다시 실패하면 더 오래 쉬게 한다.
    public void onGoalSwitched(GoalType previous) {
        streaks.remove(previous);
    }

    public int failureStreak(GoalType goal) {
        return streaks.getOrDefault(goal, 0);
    }

    public int restCount(GoalType goal) {
        return rests.getOrDefault(goal, 0);
    }

    public void reset() {
        streaks.clear();
        rests.clear();
    }
}
