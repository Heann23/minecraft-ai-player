package me.herry.minecraftAI.ai.autonomy;

import java.util.List;
import java.util.Map;

/** Independent, renewable outcome conditions. List order is not a progression script. */
public final class OverworldObjectives {
    private static final List<AutonomyGoal> OBJECTIVES = List.of(
            new AutonomyGoal("nether-portal", Map.of("world:portal", 1), 120),
            new AutonomyGoal("food-reserve", Map.of("stock:food", 8), 140),
            new AutonomyGoal("ready-food", Map.of("stock:ready-food", 4), 85),
            new AutonomyGoal("safe-home", Map.of("world:shelter", 1), 90),
            new AutonomyGoal("stone-mining", Map.of("cap:pickaxe:STONE", 1), 100),
            new AutonomyGoal("work-pickaxe", Map.of("item:STONE_PICKAXE", 1), 45),
            new AutonomyGoal("iron-mining", Map.of("cap:pickaxe:IRON", 1), 80),
            new AutonomyGoal("iron-weapon", Map.of("cap:sword:IRON", 1), 65),
            new AutonomyGoal("iron-protection-head", Map.of("cap:armor:IRON:HEAD", 1), 60),
            new AutonomyGoal("iron-protection-chest", Map.of("cap:armor:IRON:CHEST", 1), 60),
            new AutonomyGoal("iron-protection-legs", Map.of("cap:armor:IRON:LEGS", 1), 60),
            new AutonomyGoal("iron-protection-feet", Map.of("cap:armor:IRON:FEET", 1), 60),
            new AutonomyGoal("shield", Map.of("item:SHIELD", 1), 55),
            new AutonomyGoal("lighting", Map.of("item:TORCH", 8), 55),
            new AutonomyGoal("diamond-mining", Map.of("cap:pickaxe:DIAMOND", 1), 50),
            new AutonomyGoal("water", Map.of("item:WATER_BUCKET", 1), 45),
            new AutonomyGoal("ignition", Map.of("item:FLINT_AND_STEEL", 1), 30));

    private OverworldObjectives() {}

    public static List<AutonomyGoal> goals(PlanningState state) {
        return OBJECTIVES.stream().filter(goal -> !goal.achieved(state)).toList();
    }

    /** Equipment and a house are alternative stabilizing outcomes, not mandatory gates to an already usable portal. */
    public static boolean ready(PlanningState state) {
        return state.count("world:portal") > 0;
    }
}
