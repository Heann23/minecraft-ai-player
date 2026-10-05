package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.plan.Planner;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 새로 만든 범용 목표(기존 GoalType 에서 온 것이 아닌 목표)를, 지금 상황에 맞는 기존 계획 함수로 수행하는 스킬의 공통 부분.
 * 예: "철 원석 3개"는 아는 철 광석이 있으면 MINE_IRON 의 계획으로, 없으면 FIND_IRON 의 계획으로 수행한다.
 *
 * 어느 계획으로 갈지(route)는 값만 보고 정하므로 서버 없이 시험할 수 있다.
 * 기존 GoalType 을 옮겨 적은 목표는 맡지 않는다. 그런 목표는 LegacyPlannerSkill 이 전과 똑같이 수행한다.
 */
public abstract class RoutedSkill implements Skill {
    // 이미 아는 것을 하러 가는 일과, 찾아다녀야 하는 일의 어림 비용
    protected static final double KNOWN_COST = 20.0;
    protected static final double SEARCH_COST = 60.0;

    private final String name;
    private final Planner planner;

    protected RoutedSkill(String name, Planner planner) {
        this.name = Objects.requireNonNull(name, "name");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    // 맡을 수 있는 종류의 목표인지 (상황과 상관없이).
    protected abstract boolean handles(Goal goal);

    // 지금 상황에서 이 목표를 어느 기존 목표의 계획으로 수행할지. 지금은 할 수 없으면 null.
    public abstract @Nullable GoalType route(Goal goal, Situation situation);

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final boolean supports(Goal goal) {
        return goal.metadata().legacyOrigin() == null && handles(goal);
    }

    @Override
    public final boolean canExecute(Goal goal, @Nullable Situation situation) {
        return situation != null && route(goal, situation) != null;
    }

    @Override
    public double estimateCost(Goal goal, @Nullable Situation situation) {
        GoalType route = situation == null ? null : route(goal, situation);
        if (route == null) return Double.MAX_VALUE;
        return route.name().startsWith("FIND_") || route == GoalType.EXPLORE ? SEARCH_COST : KNOWN_COST;
    }

    @Override
    public final List<Action> buildPlan(AIPlayer ai, Goal goal, @Nullable Situation situation) {
        GoalType route = situation == null ? null : route(goal, situation);
        return route == null ? List.of() : planner.plan(route, ai);
    }
}
