package me.herry.minecraftAI.ai.autonomy;

import me.herry.minecraftAI.ai.plan.IrrigationPlans;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IrrigationPlanningTest {
    private static final BlockPoint CELL = new BlockPoint(2, 63, 4);
    private static final IrrigationPlans.Site SITE = new IrrigationPlans.Site(UUID.randomUUID(), CELL, true, 3, 12);

    @Test void emptyBucketNeedsFillingBeforeIrrigationAndDoesNotPromiseAHarvest() {
        var install = IrrigationPlanning.install(SITE);
        var fill = new ActionModel("fill", Map.of("item:BUCKET", 1), Map.of("item:BUCKET", 1),
                Map.of("item:WATER_BUCKET", 1), 3);
        var before = new PlanningState(Map.of(IrrigationPlanning.site(CELL), 1, "item:BUCKET", 1));
        var goal = new AutonomyGoal("water", Map.of(IrrigationPlanning.water(CELL), 1), 20);
        var search = new ForwardSearch(before, List.of(install, fill), goal);
        while (search.status() == ForwardSearch.Status.SEARCHING) search.advance(16, () -> false);
        assertEquals(ForwardSearch.Status.SOLVED, search.status());
        assertEquals(List.of("fill", install.id()), search.plan().stream().map(ActionModel::id).toList());
        var after = before.apply(fill).apply(install);
        assertEquals(1, after.count("item:BUCKET"));
        assertEquals(0, after.count("item:WATER_BUCKET"));
        assertEquals(0, after.count("world:crop-planted"));
        assertEquals(0, after.count("stock:food"));
        assertFalse(after.satisfies(install.preconditions()));
    }

    @Test void successfulActionStatusWithoutObservedWaterDoesNotCompleteTheWaterGoal() {
        var before = new PlanningState(Map.of(IrrigationPlanning.site(CELL), 1, "item:WATER_BUCKET", 1));
        var consumedOnly = new PlanningState(Map.of("item:BUCKET", 1));
        var goal = new AutonomyGoal("water", Map.of(IrrigationPlanning.water(CELL), 1), 20);
        assertFalse(goal.achieved(consumedOnly));
        assertNotEquals(AutonomyFeedback.Outcome.ACHIEVED,
                AutonomyFeedback.assess(goal, IrrigationPlanning.install(SITE), before, consumedOnly));
    }

    @Test void irrigationIsConditionalAndIsNotAMandatoryGateToThePortal() {
        var dry = new PlanningState(Map.of("item:WHEAT_SEEDS", 1, IrrigationPlanning.site(CELL), 1));
        assertTrue(OverworldObjectives.goals(dry).stream().anyMatch(goal -> goal.id().startsWith("farm-water:")));
        var plantable = new PlanningState(Map.of("item:WHEAT_SEEDS", 1, IrrigationPlanning.site(CELL), 1,
                FarmPlanning.soil(CELL.offset(1, 0, 0)), 1));
        assertFalse(OverworldObjectives.goals(plantable).stream().anyMatch(goal -> goal.id().startsWith("farm-water:")));
        assertTrue(OverworldObjectives.ready(new PlanningState(Map.of("world:portal", 1))));
        assertFalse(OverworldObjectives.goals(new PlanningState(Map.of(IrrigationPlanning.site(CELL), 1)))
                .stream().anyMatch(goal -> goal.id().startsWith("farm-water:")));
    }
}
