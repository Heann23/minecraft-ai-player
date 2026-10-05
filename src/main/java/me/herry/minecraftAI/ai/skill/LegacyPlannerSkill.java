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
 * 기존 GoalType 하나의 계획 함수를 스킬로 감싼 것. Planner 에 등록된 GoalType 마다 하나씩 있다.
 *
 * 그 GoalType 을 옮겨 적은 목표(metadata.legacyOrigin)만 맡고, 그런 목표는 이 스킬 하나만 맡는다.
 * 그래서 GoalSystem 이 고른 목표의 계획은 스킬 계층을 지나가도 전과 똑같다.
 * 지금 할 수 있는지는 GoalSystem 이 이미 따졌고, 그래도 안 되면 계획 함수가 빈 목록을 돌려준다.
 */
public final class LegacyPlannerSkill implements Skill {
    private final GoalType type;
    private final Planner.GoalPlanner planner;

    public LegacyPlannerSkill(GoalType type, Planner.GoalPlanner planner) {
        this.type = Objects.requireNonNull(type, "type");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public static String nameOf(GoalType type) {
        return type.name();
    }

    @Override
    public String name() {
        return nameOf(type);
    }

    @Override
    public boolean supports(Goal goal) {
        return goal.metadata().legacyOrigin() == type;
    }

    @Override
    public boolean canExecute(Goal goal, @Nullable Situation situation) {
        return true;
    }

    @Override
    public double estimateCost(Goal goal, @Nullable Situation situation) {
        return 0.0;
    }

    @Override
    public List<Action> buildPlan(AIPlayer ai, Goal goal, @Nullable Situation situation) {
        return planner.plan(ai);
    }
}
