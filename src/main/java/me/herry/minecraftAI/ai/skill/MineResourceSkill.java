package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.observation.ItemGroups;
import me.herry.minecraftAI.ai.plan.Planner;
import org.jetbrains.annotations.Nullable;

/**
 * 돌, 석탄, 철 원석, 다이아몬드를 캐서 얻는다. 아는 광석이 있으면 캐러 가고, 없으면 굴을 파 내려가며 찾는다.
 * 그 광물을 캘 수 있는 곡괭이가 없으면 쓸 수 없다 (곡괭이를 만드는 것은 다른 목표의 일이다).
 */
public final class MineResourceSkill extends RoutedSkill {
    private enum Resource { STONE, COAL, IRON, DIAMOND }

    public MineResourceSkill(Planner planner) {
        super("MineResource", planner);
    }

    @Override
    protected boolean handles(Goal goal) {
        return resourceOf(goal) != null;
    }

    @Override
    public @Nullable GoalType route(Goal goal, Situation s) {
        Resource resource = resourceOf(goal);
        if (resource == null) return null;
        return switch (resource) {
            case STONE -> s.hasPickaxe ? GoalType.MINE_STONE : null;
            // 석탄은 따로 찾으러 다니지 않는다. 철을 찾아 굴을 파 내려가다 보면 나온다.
            case COAL -> !s.hasPickaxe ? null : s.knowsCoal ? GoalType.MINE_COAL : GoalType.FIND_IRON;
            case IRON -> !s.canMineIron ? null : s.knowsIron ? GoalType.MINE_IRON : GoalType.FIND_IRON;
            case DIAMOND -> !s.canMineDiamond ? null : s.knowsDiamond ? GoalType.MINE_DIAMOND : GoalType.FIND_DIAMOND;
        };
    }

    private static @Nullable Resource resourceOf(Goal goal) {
        if (!(goal instanceof AcquireGoal acquire)) return null;
        return switch (acquire.item()) {
            case ItemGroups.STONE, "COBBLESTONE" -> Resource.STONE;
            case ItemGroups.COAL, "COAL" -> Resource.COAL;
            case "RAW_IRON" -> Resource.IRON;
            case "DIAMOND" -> Resource.DIAMOND;
            default -> null;
        };
    }
}
