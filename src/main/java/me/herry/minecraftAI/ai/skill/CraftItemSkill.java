package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.CraftGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.plan.CraftPlans;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 제작법이 있는 아이템을 가진 재료로 만든다. 작업대가 필요하면 가까운 것을 쓰거나 가진 것을 놓는다.
 * 재료를 구하러 가지는 않는다. 재료가 모자라면 계획이 서지 않는다 (재료부터 구하는 것은 목표를 나누는 쪽의 일이다).
 *
 * 작업대와 횃불은 DirectRouteSkill 이 맡는다 (작업대는 놓는 데까지 해야 하고, 횃불은 가진 석탄만큼만 만든다).
 */
public final class CraftItemSkill implements Skill {
    private static final double COST = 10.0;

    @Override
    public String name() {
        return "CraftItem";
    }

    @Override
    public boolean supports(Goal goal) {
        return goal.metadata().legacyOrigin() == null && goal instanceof CraftGoal craft
                && !craft.item().equals(CraftGoal.CRAFTING_TABLE) && !craft.item().equals("TORCH");
    }

    // 재료가 되는지는 가방을 봐야 알 수 있어서 계획을 세울 때 따진다.
    @Override
    public boolean canExecute(Goal goal, @Nullable Situation situation) {
        return true;
    }

    @Override
    public double estimateCost(Goal goal, @Nullable Situation situation) {
        return COST;
    }

    @Override
    public List<Action> buildPlan(AIPlayer ai, Goal goal, @Nullable Situation situation) {
        if (!(goal instanceof CraftGoal craft)) return List.of();
        Material material = Material.getMaterial(craft.item());
        if (material == null) return List.of();
        // 목표의 개수는 "가지고 있을 양"이므로 모자란 만큼만 만든다.
        int missing = craft.count() - ai.getInventory().count(material);
        return missing <= 0 ? List.of() : CraftPlans.craftItem(ai, material, missing);
    }
}
