package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.goal.model.SurviveGoal;
import me.herry.minecraftAI.ai.observation.ItemGroups;
import me.herry.minecraftAI.ai.plan.Planner;
import org.jetbrains.annotations.Nullable;

/**
 * 사냥해서 음식을 얻고, 배가 고프면 먹는다. 음식을 얻는 방법은 지금 사냥(과 주운 것 먹기)뿐이다.
 * 음식의 종류는 고르지 못한다.
 */
public final class HuntPreySkill extends RoutedSkill {
    public HuntPreySkill(Planner planner) {
        super("HuntPrey", planner);
    }

    @Override
    protected boolean handles(Goal goal) {
        if (goal instanceof AcquireGoal acquire) return acquire.item().equals(ItemGroups.FOOD);
        return goal instanceof SurviveGoal survive && survive.reason() == SurviveGoal.Reason.EAT;
    }

    @Override
    public @Nullable GoalType route(Goal goal, Situation s) {
        if (!handles(goal)) return null;
        // 먹는 것이 목적이면 가진 것을 먹거나 구하러 가고, 모으는 것이 목적이면 사냥해서 쌓아 둔다.
        return goal instanceof SurviveGoal ? GoalType.FIND_FOOD : GoalType.STOCK_FOOD;
    }
}
