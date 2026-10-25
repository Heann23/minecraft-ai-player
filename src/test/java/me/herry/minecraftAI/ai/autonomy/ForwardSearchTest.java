package me.herry.minecraftAI.ai.autonomy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ForwardSearchTest {
    @Test
    void composesResourceAcquisitionAndCraftingFromActualPreconditions() {
        ActionModel gather = action("gather-wood", Map.of(), Map.of(), Map.of("wood", 1), 2);
        ActionModel table = action("make-table", Map.of("wood", 2), Map.of("wood", 2), Map.of("table", 1), 1);
        ActionModel pick = action("make-pick", Map.of("table", 1, "wood", 1), Map.of("wood", 1), Map.of("pick", 1), 1);
        var search = search(Map.of(), List.of(gather, table, pick), Map.of("pick", 1));

        solve(search);

        assertEquals(ForwardSearch.Status.SOLVED, search.status());
        assertEquals(8, search.cost());
        PlanningState actual = new PlanningState(Map.of());
        for (ActionModel step : search.plan()) actual = actual.apply(step);
        assertTrue(actual.satisfies(Map.of("pick", 1)));
        assertEquals(0, actual.count("wood"));
        assertEquals(1, actual.count("table"));
    }

    @Test
    void ownedUpperCapabilitySkipsItsLowerProductionRoute() {
        var search = search(Map.of("iron-pick", 1), List.of(
                action("craft-iron", Map.of("stone-pick", 1), Map.of(), Map.of("iron-pick", 1), 100),
                action("mine-diamond", Map.of("iron-pick", 1), Map.of(), Map.of("diamond", 1), 4)),
                Map.of("diamond", 1));

        solve(search);

        assertEquals(List.of("mine-diamond"), search.plan().stream().map(ActionModel::id).toList());
        assertEquals(4, search.cost());
    }

    @Test
    void choosesCheaperAlternativeAndChangesWithAvailableStock() {
        List<ActionModel> alternatives = List.of(
                action("expensive-direct", Map.of(), Map.of(), Map.of("tool", 1), 9),
                action("gather", Map.of(), Map.of(), Map.of("metal", 1), 2),
                action("craft", Map.of("metal", 2), Map.of("metal", 2), Map.of("tool", 1), 1));
        var empty = search(Map.of(), alternatives, Map.of("tool", 1));
        var owned = search(Map.of("metal", 2), alternatives, Map.of("tool", 1));

        solve(empty);
        solve(owned);

        assertEquals(5, empty.cost());
        assertEquals(1, owned.cost());
        assertEquals(List.of("craft"), owned.plan().stream().map(ActionModel::id).toList());
    }

    @Test
    void cannotSpendOneResourceTwice() {
        var search = search(Map.of("ingot", 1), List.of(
                action("shield", Map.of("ingot", 1), Map.of("ingot", 1), Map.of("shield", 1), 1),
                action("bucket", Map.of("ingot", 1), Map.of("ingot", 1), Map.of("bucket", 1), 1)),
                Map.of("shield", 1, "bucket", 1));

        solve(search);

        assertEquals(ForwardSearch.Status.UNREACHABLE, search.status());
        assertEquals(List.of(), search.plan());
        assertEquals(Double.POSITIVE_INFINITY, search.cost());
    }

    @Test
    void alreadySatisfiedGoalNeedsNoVirtualActions() {
        var search = search(Map.of("food", 8), List.of(), Map.of("food", 6));

        assertEquals(ForwardSearch.Status.SOLVED, search.status());
        assertEquals(0, search.cost());
        assertEquals(0, search.expanded());
        assertTrue(search.plan().isEmpty());
    }

    @Test
    void missingCapabilityIsUnreachableRatherThanInvented() {
        var search = search(Map.of("wood", 20), List.of(
                action("wooden-tool", Map.of("wood", 2), Map.of("wood", 2), Map.of("wooden-tool", 1), 1)),
                Map.of("unsupported-portal", 1));

        solve(search);

        assertEquals(ForwardSearch.Status.UNREACHABLE, search.status());
    }

    @Test
    void repeatedNonconsumedFactsAndNoopsDoNotCreateUnboundedStates() {
        var search = search(Map.of(), List.of(
                action("table", Map.of(), Map.of(), Map.of("table", 1), 1),
                action("noop", Map.of("table", 1), Map.of(), Map.of("table", 1), 1),
                action("unavailable", Map.of("table", 1, "missing", 1), Map.of(), Map.of("goal", 1), 1)),
                Map.of("goal", 1));

        solve(search);

        assertEquals(ForwardSearch.Status.UNREACHABLE, search.status());
        assertTrue(search.expanded() < 10);
    }

    @Test
    void workBudgetPausesAndResumesWithoutLosingAnOutgoingAction() {
        List<ActionModel> actions = List.of(
                action("a-expensive", Map.of(), Map.of(), Map.of("goal", 1), 20),
                action("b-gather", Map.of(), Map.of(), Map.of("part", 1), 1),
                action("c-finish", Map.of("part", 1), Map.of("part", 1), Map.of("goal", 1), 1));
        var search = search(Map.of(), actions, Map.of("goal", 1));
        assertEquals(ForwardSearch.Status.SEARCHING, search.advance(2, () -> true));
        assertEquals(0, search.expanded());

        for (int attempt = 0; attempt < 200 && search.status() == ForwardSearch.Status.SEARCHING; attempt++) {
            AtomicInteger checks = new AtomicInteger();
            search.advance(1, () -> checks.incrementAndGet() > 3);
        }

        assertEquals(ForwardSearch.Status.SOLVED, search.status());
        assertEquals(List.of("b-gather", "c-finish"), search.plan().stream().map(ActionModel::id).toList());
        assertEquals(2, search.cost());
    }

    @Test
    void depthLimitIsReportedSeparatelyFromProofOfUnreachability() {
        List<ActionModel> chain = new ArrayList<>();
        for (int index = 0; index <= ForwardSearch.MAX_DEPTH; index++) {
            chain.add(action("step-" + index, Map.of("stage-" + index, 1), Map.of("stage-" + index, 1),
                    Map.of("stage-" + (index + 1), 1), 1));
        }
        var search = search(Map.of("stage-0", 1), chain, Map.of("stage-" + (ForwardSearch.MAX_DEPTH + 1), 1));

        solve(search);

        assertEquals(ForwardSearch.Status.LIMIT_REACHED, search.status());
        assertTrue(search.plan().isEmpty());
    }

    @Test
    void resourceCapDoesNotCutMaterialNeededBySeveralCrafts() {
        var search = search(Map.of(), List.of(
                action("gather", Map.of(), Map.of(), Map.of("ore", 1), 1),
                action("first", Map.of("ore", 3), Map.of("ore", 3), Map.of("first", 1), 1),
                action("second", Map.of("ore", 4), Map.of("ore", 4), Map.of("second", 1), 1)),
                Map.of("first", 1, "second", 1));

        solve(search);

        assertEquals(ForwardSearch.Status.SOLVED, search.status());
        assertEquals(9, search.cost());
    }

    @Test
    void inputsAndReturnedPlansAreImmutableAndZeroStockIsCanonical() {
        Map<String, Integer> quantities = new HashMap<>(Map.of("wood", 3, "zero", 0));
        PlanningState state = new PlanningState(quantities);
        quantities.clear();
        assertEquals(new PlanningState(Map.of("wood", 3)), state);
        assertThrows(UnsupportedOperationException.class, () -> state.quantities().clear());
        assertThrows(IllegalArgumentException.class, () -> new PlanningState(Map.of("wood", -1)));
        assertThrows(IllegalArgumentException.class,
                () -> action("unsafe", Map.of("wood", 1), Map.of("wood", 2), Map.of("tool", 1), 1));
        assertThrows(IllegalArgumentException.class, () -> action("free", Map.of(), Map.of(), Map.of(), 0));

        var search = search(Map.of(), List.of(action("gather", Map.of(), Map.of(), Map.of("wood", 1), 1)),
                Map.of("wood", 1));
        solve(search);
        assertThrows(UnsupportedOperationException.class, () -> search.plan().clear());
    }

    private static ForwardSearch search(Map<String, Integer> initial, List<ActionModel> actions,
                                        Map<String, Integer> required) {
        return new ForwardSearch(new PlanningState(initial), actions, new AutonomyGoal("test", required, 1));
    }

    private static ActionModel action(String id, Map<String, Integer> required, Map<String, Integer> consumed,
                                       Map<String, Integer> effects, double cost) {
        return new ActionModel(id, required, consumed, effects, cost);
    }

    private static void solve(ForwardSearch search) {
        for (int attempt = 0; attempt < 10_000 && search.status() == ForwardSearch.Status.SEARCHING; attempt++) {
            search.advance(32, () -> false);
        }
        assertNotEquals(ForwardSearch.Status.SEARCHING, search.status(), "bounded search must terminate");
    }
}
