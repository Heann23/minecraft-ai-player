package me.herry.minecraftAI.ai.autonomy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SupportSearchTest {
    private static SupportSearch solve(Map<String, Integer> initial, List<ActionModel> actions, Map<String, Integer> required) {
        var search = new SupportSearch(new PlanningState(initial), actions, new AutonomyGoal("result", required, 10));
        while (search.status() == SupportSearch.Status.SEARCHING) search.advance(8, () -> false);
        return search;
    }

    @Test void derivesFirstExecutableStepFromMissingConditionsWithoutResourceNames() {
        var finish = new ActionModel("finish", Map.of("part", 3), Map.of("part", 3), Map.of("result", 1), 2);
        var prepare = new ActionModel("prepare", Map.of("source", 1), Map.of("source", 1), Map.of("part", 2), 4);
        var collect = new ActionModel("collect", Map.of(), Map.of(), Map.of("source", 1), 3);
        var search = solve(Map.of(), List.of(finish, prepare, collect), Map.of("result", 1));
        assertEquals(SupportSearch.Status.READY, search.status());
        assertEquals("collect", search.choices().getFirst().action().id());
        assertEquals(List.of("finish", "prepare", "collect"), search.choices().getFirst().dependencyPath());
        assertEquals(16, search.choices().getFirst().cost());
        assertFalse(new AutonomyGoal("result", Map.of("result", 1), 10).achieved(new PlanningState(Map.of())));
    }

    @Test void skipsAlreadySatisfiedNeedsAndChoosesActualExecutableAction() {
        var finish = new ActionModel("finish", Map.of("part", 3), Map.of("part", 3), Map.of("result", 1), 2);
        var collect = new ActionModel("collect", Map.of(), Map.of(), Map.of("part", 1), 1);
        var search = solve(Map.of("part", 3), List.of(finish, collect), Map.of("result", 1));
        assertEquals(List.of("finish"), search.choices().stream().map(choice -> choice.action().id()).toList());
        assertTrue(new PlanningState(Map.of("part", 3)).satisfies(search.choices().getFirst().action().preconditions()));
    }

    @Test void closedDependencyCycleIsNotWorldImpossibilityOrAnExecutableChoice() {
        var a = new ActionModel("a", Map.of("b", 1), Map.of("b", 1), Map.of("a", 1), 1);
        var b = new ActionModel("b", Map.of("a", 1), Map.of("a", 1), Map.of("b", 1), 1);
        var search = solve(Map.of(), List.of(a, b), Map.of("a", 1));
        assertEquals(SupportSearch.Status.NO_CANDIDATE, search.status());
        assertTrue(search.choices().isEmpty());
    }

    @Test void aSupportedAlternativeCanEscapeACyclicRecipe() {
        var a = new ActionModel("a", Map.of("b", 1), Map.of("b", 1), Map.of("a", 1), 1);
        var b = new ActionModel("b", Map.of("a", 1), Map.of("a", 1), Map.of("b", 1), 1);
        var other = new ActionModel("other", Map.of("available", 1), Map.of("available", 1), Map.of("a", 1), 5);
        var search = solve(Map.of("available", 1), List.of(a, b, other), Map.of("a", 1));
        assertEquals(SupportSearch.Status.READY, search.status());
        assertEquals("other", search.choices().getFirst().action().id());
    }

    @Test void unrelatedProductionAndZeroNetEffectsDoNotBecomeSupport() {
        var unrelated = new ActionModel("unrelated", Map.of(), Map.of(), Map.of("unrelated", 100), 1);
        var noGain = new ActionModel("no-gain", Map.of("wanted", 1), Map.of("wanted", 1), Map.of("wanted", 1), 1);
        var search = solve(Map.of(), List.of(unrelated, noGain), Map.of("wanted", 1));
        assertEquals(SupportSearch.Status.NO_CANDIDATE, search.status());
    }

    @Test void amountAndDownstreamCostsAreIncludedAndCandidatesCompete() {
        var finish = new ActionModel("finish", Map.of("part", 3), Map.of("part", 3), Map.of("result", 1), 2);
        var small = new ActionModel("small", Map.of(), Map.of(), Map.of("part", 1), 2);
        var large = new ActionModel("large", Map.of(), Map.of(), Map.of("part", 3), 5);
        var search = solve(Map.of(), List.of(finish, small, large), Map.of("result", 1));
        assertEquals(List.of("large", "small"), search.choices().stream().map(choice -> choice.action().id()).toList());
        assertEquals(7, search.choices().getFirst().cost());
        assertEquals(8, search.choices().getLast().cost());
    }

    @Test void budgetPausesResumeInsideProducerAndPrerequisiteTraversal() {
        var actions = List.of(
                new ActionModel("finish", Map.of("a", 1, "b", 1), Map.of("a", 1, "b", 1), Map.of("result", 1), 2),
                new ActionModel("get-a", Map.of(), Map.of(), Map.of("a", 1), 1),
                new ActionModel("get-b", Map.of(), Map.of(), Map.of("b", 1), 1));
        var resumed = new SupportSearch(new PlanningState(Map.of()), actions, new AutonomyGoal("result", Map.of("result", 1), 10));
        int calls = 0;
        while (resumed.status() == SupportSearch.Status.SEARCHING && calls++ < 100) {
            var checks = new AtomicInteger();
            resumed.advance(1, () -> checks.incrementAndGet() > 2);
        }
        var uninterrupted = solve(Map.of(), actions, Map.of("result", 1));
        assertEquals(SupportSearch.Status.READY, resumed.status());
        assertEquals(uninterrupted.choices(), resumed.choices());
    }

    @Test void depthExhaustionRemainsDistinctFromNoCandidate() {
        var actions = new ArrayList<ActionModel>();
        for (int i = 0; i <= SupportSearch.MAX_DEPTH; i++) {
            actions.add(new ActionModel("step-" + i, Map.of("n" + (i + 1), 1), Map.of(), Map.of("n" + i, 1), 1));
        }
        actions.add(new ActionModel("source", Map.of(), Map.of(), Map.of("n" + (SupportSearch.MAX_DEPTH + 1), 1), 1));
        var search = solve(Map.of(), actions, Map.of("n0", 1));
        assertEquals(SupportSearch.Status.LIMIT_REACHED, search.status());
        assertTrue(search.choices().isEmpty());
    }

    @Test void supportedCandidateSurvivesAnotherBranchReachingTheDepthLimit() {
        var actions = new ArrayList<ActionModel>();
        for (int i = 0; i <= SupportSearch.MAX_DEPTH; i++) {
            actions.add(new ActionModel("step-" + i, Map.of("n" + (i + 1), 1), Map.of(), Map.of("n" + i, 1), 1));
        }
        actions.add(new ActionModel("direct", Map.of(), Map.of(), Map.of("n0", 1), 100));
        var search = solve(Map.of(), actions, Map.of("n0", 1));
        assertEquals(SupportSearch.Status.READY, search.status());
        assertEquals("direct", search.choices().getFirst().action().id());
    }

    @Test void nodeExhaustionDoesNotClaimTheWorldOrModelHasNoRoute() {
        var required = new java.util.HashMap<String, Integer>();
        for (int i = 0; i <= SupportSearch.MAX_NODES; i++) required.put("need-" + i, 1);
        var search = solve(Map.of(), List.of(), required);
        assertEquals(SupportSearch.Status.LIMIT_REACHED, search.status());
        assertTrue(search.choices().isEmpty());
    }

    @Test void capsCandidateCountAndReturnsImmutableDependencyPaths() {
        var actions = new ArrayList<ActionModel>();
        for (int i = 0; i < 12; i++) actions.add(new ActionModel("option-" + i, Map.of(), Map.of(), Map.of("result", 1), i + 1));
        var search = solve(Map.of(), actions, Map.of("result", 1));
        assertEquals(SupportSearch.MAX_CHOICES, search.choices().size());
        assertThrows(UnsupportedOperationException.class, () -> search.choices().clear());
        assertThrows(UnsupportedOperationException.class, () -> search.choices().getFirst().dependencyPath().clear());
    }

    @Test void zeroWorkAndAlreadyAchievedGoalDoNotCreateFakeCandidates() {
        var source = new ActionModel("source", Map.of(), Map.of(), Map.of("result", 1), 1);
        var pending = new SupportSearch(new PlanningState(Map.of()), List.of(source), new AutonomyGoal("result", Map.of("result", 1), 10));
        assertEquals(SupportSearch.Status.SEARCHING, pending.advance(0, () -> false));
        assertTrue(pending.choices().isEmpty());
        assertEquals(SupportSearch.Status.NO_CANDIDATE, solve(Map.of("result", 1), List.of(source), Map.of("result", 1)).status());
    }
}
