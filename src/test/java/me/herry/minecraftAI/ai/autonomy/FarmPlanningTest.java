package me.herry.minecraftAI.ai.autonomy;

import me.herry.minecraftAI.ai.plan.FarmingPlans;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FarmPlanningTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final BlockPoint SOIL = new BlockPoint(2, 63, 2);

    private static FarmingPlans.PlantingSite site(Material seed, Material crop, boolean till) {
        return new FarmingPlans.PlantingSite(WORLD, SOIL, seed, crop, till, true, true, 12);
    }

    @Test void plantingAddsProductionCapacityAndSpendsOneSeedWithoutInventingFood() {
        var candidate = site(Material.WHEAT_SEEDS, Material.WHEAT, true);
        var operation = FarmPlanning.planting(candidate, 1);
        var before = new PlanningState(Map.of(FarmPlanning.site(Material.WHEAT_SEEDS, SOIL), 1,
                FarmPlanning.soil(SOIL), 1, "item:WHEAT_SEEDS", 2, "cap:hoe", 1));
        var after = before.apply(operation);
        assertEquals(1, after.count("item:WHEAT_SEEDS"));
        assertEquals(1, after.count("world:crop-planted"));
        assertEquals(0, after.count("stock:food"));
        assertEquals(0, after.count("item:WHEAT"));
        assertFalse(before.satisfies(FarmPlanning.planting(candidate, 3).preconditions()));
    }

    @Test void theSameSoilCannotBeUsedAsSeveralIndependentPlants() {
        var candidate = site(Material.WHEAT_SEEDS, Material.WHEAT, false);
        var operation = FarmPlanning.planting(candidate, 1);
        var before = new PlanningState(Map.of(FarmPlanning.site(Material.WHEAT_SEEDS, SOIL), 1,
                FarmPlanning.soil(SOIL), 1, "item:WHEAT_SEEDS", 4));
        var search = new ForwardSearch(before, List.of(operation), new AutonomyGoal("two-crops", Map.of("world:crop-planted", 2), 10));
        while (search.status() == ForwardSearch.Status.SEARCHING) search.advance(16, () -> false);
        assertEquals(ForwardSearch.Status.UNREACHABLE, search.status());
    }

    @Test void unknownPlotsAreNotLivingCropsAndKeepAPropagationReserve() {
        var unknown = new FarmingPlans.Crop(WORLD, SOIL, Material.AIR, Material.AIR,
                FarmingPlans.Stage.UNKNOWN, -1, -1, false, false, -1);
        var snapshot = new FarmingPlans.Snapshot(List.of(), List.of(unknown), false);
        assertEquals(0, FarmPlanning.observed(snapshot).getOrDefault("world:crop-planted", 0));
        assertEquals(2, FarmPlanning.requiredSeedCount(snapshot, Material.WHEAT_SEEDS));
        assertEquals(1, FarmPlanning.requiredSeedCount(new FarmingPlans.Snapshot(List.of(), List.of(), true), Material.WHEAT_SEEDS));
    }

    @Test void ediblePropagationIsRemovedFromFoodStockWhenPlanted() {
        var operation = FarmPlanning.planting(site(Material.CARROT, Material.CARROTS, false), 2);
        var state = new PlanningState(Map.of(FarmPlanning.site(Material.CARROT, SOIL), 1,
                FarmPlanning.soil(SOIL), 1, "item:CARROT", 2, "stock:food", 2, "stock:ready-food", 2));
        var after = state.apply(operation);
        assertEquals(1, after.count("item:CARROT"));
        assertEquals(1, after.count("stock:food"));
        assertEquals(1, after.count("stock:ready-food"));
    }

    @Test void inspectingGrowthIsKnowledgeAndDoesNotCompleteAFoodObjective() {
        var food = new AutonomyGoal("food", Map.of("stock:food", 8), 10);
        var inspect = new ActionModel("inspect", Map.of(FarmPlanning.due(SOIL), 1), Map.of(FarmPlanning.due(SOIL), 1),
                Map.of(FarmPlanning.checked(SOIL), 1), 1);
        var before = new PlanningState(Map.of(FarmPlanning.due(SOIL), 1));
        var after = before.apply(inspect);
        assertFalse(food.achieved(after));
        assertEquals(AutonomyFeedback.Outcome.PROGRESSED, AutonomyFeedback.assess(food, inspect, before, after));
    }

    @Test void differentSpeciesStillCompeteForTheSamePhysicalCell() {
        var wheat = FarmPlanning.planting(site(Material.WHEAT_SEEDS, Material.WHEAT, false), 1);
        var carrot = FarmPlanning.planting(site(Material.CARROT, Material.CARROTS, false), 1);
        var before = new PlanningState(Map.of(FarmPlanning.site(Material.WHEAT_SEEDS, SOIL), 1,
                FarmPlanning.site(Material.CARROT, SOIL), 1, FarmPlanning.soil(SOIL), 1,
                "item:WHEAT_SEEDS", 1, "item:CARROT", 1, "stock:food", 1, "stock:ready-food", 1));
        assertFalse(before.apply(wheat).satisfies(carrot.preconditions()));
    }
}
