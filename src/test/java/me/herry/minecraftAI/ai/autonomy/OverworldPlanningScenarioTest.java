package me.herry.minecraftAI.ai.autonomy;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Pure planning scenarios; these do not claim to exercise a Paper world or FakePlayer physics. */
class OverworldPlanningScenarioTest {
    private static final AutonomyGoal TOOL = new AutonomyGoal("tool", Map.of("pick", 1), 10);
    private static final ActionModel HARVEST = new ActionModel("harvest", Map.of(), Map.of(), Map.of("logs", 1), 5);
    private static final ActionModel PLANKS = new ActionModel("planks", Map.of("logs", 1), Map.of("logs", 1), Map.of("planks", 4), 1);
    private static final ActionModel TOOL_RECIPE = new ActionModel("craft", Map.of("planks", 3, "stick", 2),
            Map.of("planks", 3, "stick", 2), Map.of("pick", 1), 1);
    private static final ActionModel STICKS = new ActionModel("sticks", Map.of("planks", 2), Map.of("planks", 2), Map.of("stick", 4), 1);

    private static ForwardSearch solve(Map<String, Integer> facts, List<ActionModel> actions, AutonomyGoal goal) {
        var search = new ForwardSearch(new PlanningState(facts), actions, goal);
        while (search.status() == ForwardSearch.Status.SEARCHING) search.advance(64, () -> false);
        return search;
    }

    @Test void suppliedMaterialsSkipTheFormerWoodFirstSequence() {
        var search = solve(Map.of("planks", 3, "stick", 2), List.of(HARVEST, PLANKS, TOOL_RECIPE, STICKS), TOOL);
        assertEquals(ForwardSearch.Status.SOLVED, search.status());
        assertEquals(List.of("craft"), search.plan().stream().map(ActionModel::id).toList());
    }

    @Test void lossOfMaterialsRequiresASeparateNewPlan() {
        var stocked = solve(Map.of("planks", 3, "stick", 2), List.of(HARVEST, PLANKS, TOOL_RECIPE, STICKS), TOOL);
        var lost = solve(Map.of("stick", 2), List.of(HARVEST, PLANKS, TOOL_RECIPE, STICKS), TOOL);
        assertEquals("craft", stocked.plan().getFirst().id());
        assertEquals("harvest", lost.plan().getFirst().id());
        assertEquals(ForwardSearch.Status.SOLVED, lost.status());
    }

    @Test void chestRecoveryCompetesWithCollectionByCost() {
        var fetch = new ActionModel("fetch", Map.of("remembered:planks", 3), Map.of("remembered:planks", 3), Map.of("planks", 3), 2);
        var search = solve(Map.of("remembered:planks", 3, "stick", 2), List.of(HARVEST, PLANKS, TOOL_RECIPE, fetch), TOOL);
        assertEquals("fetch", search.plan().getFirst().id());
    }

    @Test void insertionAndCollectionAreSeparateStepsAndFuelAlternativesCompete() {
        var wood = new ActionModel("wood-fuel", Map.of("raw", 2, "planks", 2), Map.of("raw", 2, "planks", 2), Map.of("pending:2", 1), 5);
        var coal = new ActionModel("coal-fuel", Map.of("raw", 2, "coal", 1), Map.of("raw", 2, "coal", 1), Map.of("pending:2", 1), 2);
        var collect = new ActionModel("collect", Map.of("pending:2", 1), Map.of("pending:2", 1), Map.of("ingot", 2), 1);
        var goal = new AutonomyGoal("iron", Map.of("ingot", 2), 10);
        var search = solve(Map.of("raw", 2, "planks", 2, "coal", 1), List.of(wood, coal, collect), goal);
        assertEquals(List.of("coal-fuel", "collect"), search.plan().stream().map(ActionModel::id).toList());
        assertFalse(goal.achieved(new PlanningState(Map.of("pending:2", 1))));
    }

    @Test void unknownResourcesMustBeDiscoveredBeforeAcquisition() {
        var survey = new ActionModel("survey", Map.of(), Map.of(), Map.of("known:ore", 1), 3);
        var mine = new ActionModel("mine", Map.of("known:ore", 1), Map.of(), Map.of("ore", 1), 1);
        var search = solve(Map.of(), List.of(mine, survey), new AutonomyGoal("ore", Map.of("ore", 1), 10));
        assertEquals(List.of("survey", "mine"), search.plan().stream().map(ActionModel::id).toList());
    }
}
