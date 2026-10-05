package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.goal.model.SurviveGoal;
import me.herry.minecraftAI.ai.plan.Planner;
import org.jetbrains.annotations.Nullable;

/**
 * 위험에서 벗어나고(용암, 질식, 익사, 감당할 수 없는 몬스터) 체력을 회복한다.
 */
public final class EscapeDangerSkill extends RoutedSkill {
    public EscapeDangerSkill(Planner planner) {
        super("EscapeDanger", planner);
    }

    @Override
    protected boolean handles(Goal goal) {
        return goal instanceof SurviveGoal survive && survive.reason() != SurviveGoal.Reason.EAT;
    }

    @Override
    public @Nullable GoalType route(Goal goal, Situation s) {
        if (!(goal instanceof SurviveGoal survive)) return null;
        return switch (survive.reason()) {
            case ESCAPE_DANGER -> GoalType.ESCAPE_DANGER;
            case RECOVER_HEALTH -> GoalType.SURVIVE;
            case EAT -> null;
        };
    }

    // 살아남는 일은 다른 어떤 방법보다 먼저 고른다.
    @Override
    public double estimateCost(Goal goal, @Nullable Situation situation) {
        return 0.0;
    }
}
