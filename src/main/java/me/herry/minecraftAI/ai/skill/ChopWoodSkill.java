package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.observation.ItemGroups;
import me.herry.minecraftAI.ai.plan.Planner;
import org.jetbrains.annotations.Nullable;

/**
 * 나무를 베어서 얻는다. 아는 나무(또는 베다 만 나무)가 있으면 베러 가고, 없으면 찾으러 다닌다.
 * 나무의 종류는 고르지 못한다. "참나무 원목"을 달라고 해도 가까운 나무를 벤다.
 */
public final class ChopWoodSkill extends RoutedSkill {
    public ChopWoodSkill(Planner planner) {
        super("ChopWood", planner);
    }

    @Override
    protected boolean handles(Goal goal) {
        return goal instanceof AcquireGoal acquire && acquire.item().equals(ItemGroups.WOOD);
    }

    @Override
    public @Nullable GoalType route(Goal goal, Situation s) {
        if (!handles(goal)) return null;
        return s.knowsTree || s.treeUnfinished ? GoalType.COLLECT_WOOD : GoalType.FIND_WOOD;
    }
}
