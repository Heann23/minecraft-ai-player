package me.herry.minecraftAI.ai.autonomy;

import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.goal.model.GoalCategory;
import me.herry.minecraftAI.ai.goal.model.GoalMetadata;
import me.herry.minecraftAI.ai.inventory.ToolTier;
import me.herry.minecraftAI.ai.observation.Observation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Goal semantics for diagnostics/journaling, without a legacy-origin execution route. */
public record AutonomyGoalSpec(AutonomyGoal objective) implements Goal {
    @Override public GoalCategory category() { return GoalCategory.ACQUIRE; }
    @Override public GoalMetadata metadata() { return GoalMetadata.NONE; }
    @Override public String describe() { return "Satisfy(" + objective.id() + ", " + objective.required() + ")"; }

    @Override public boolean isAchieved(Observation observation) {
        Map<String, Integer> values = new HashMap<>();
        observation.inventory().itemCounts().forEach((name, count) -> values.put("item:" + name, count));
        values.put("stock:food", observation.inventory().food());
        values.put("stock:ready-food", observation.inventory().food() - observation.inventory().rawFood());
        if (observation.memory().homeSheltered()) values.put("world:shelter", 1);
        if (observation.progress().netherPortalBuilt()) values.put("world:portal", 1);
        for (String name : observation.inventory().itemCounts().keySet()) {
            if (observation.inventory().itemCounts().get(name) <= 0) continue;
            ToolTier tier = ToolTier.ofName(name);
            if (name.endsWith("_PICKAXE")) {
                for (ToolTier minimum : List.of(ToolTier.WOOD, ToolTier.STONE, ToolTier.IRON, ToolTier.DIAMOND)) {
                    if (tier.isAtLeast(minimum)) values.put("cap:pickaxe:" + minimum.name(), 1);
                }
            }
            if (name.endsWith("_SWORD") && tier.isAtLeast(ToolTier.IRON)) values.put("cap:sword:IRON", 1);
            if (name.startsWith("IRON_") || name.startsWith("DIAMOND_") || name.startsWith("NETHERITE_")) {
                if (name.endsWith("_HELMET")) values.put("cap:armor:IRON:HEAD", 1);
                if (name.endsWith("_CHESTPLATE")) values.put("cap:armor:IRON:CHEST", 1);
                if (name.endsWith("_LEGGINGS")) values.put("cap:armor:IRON:LEGS", 1);
                if (name.endsWith("_BOOTS")) values.put("cap:armor:IRON:FEET", 1);
            }
        }
        return objective.achieved(new PlanningState(values));
    }
}
