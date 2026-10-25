package me.herry.minecraftAI.ai.autonomy;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OverworldObjectivesTest {
    @Test void BetterPickaxesDoNotRequireOwningEveryObsoleteTool() {
        var state = new PlanningState(Map.of("cap:pickaxe:WOOD", 1, "cap:pickaxe:STONE", 1,
                "cap:pickaxe:IRON", 1, "cap:pickaxe:DIAMOND", 1));
        assertTrue(OverworldObjectives.goals(state).stream().noneMatch(goal -> goal.id().endsWith("mining")));
    }

    @Test void FourHelmetsCannotSubstituteForFourArmorSlots() {
        var state = new PlanningState(Map.of("cap:armor:IRON:HEAD", 4));
        assertEquals(3, OverworldObjectives.goals(state).stream().filter(goal -> goal.id().startsWith("iron-protection")).count());
    }

    @Test void LosingFoodOrShelterReopensTheCorrespondingObjective() {
        var good = new PlanningState(Map.of("stock:food", 8, "world:shelter", 1));
        assertTrue(OverworldObjectives.goals(good).stream().noneMatch(g -> g.id().equals("food-reserve") || g.id().equals("safe-home")));
        var lost = new PlanningState(Map.of());
        assertTrue(OverworldObjectives.goals(lost).stream().anyMatch(g -> g.id().equals("food-reserve")));
        assertTrue(OverworldObjectives.goals(lost).stream().anyMatch(g -> g.id().equals("safe-home")));
    }
}
