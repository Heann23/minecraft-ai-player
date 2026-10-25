package me.herry.minecraftAI.ai.autonomy;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AutonomyFeedbackTest {
    @Test void finishingMiningWithoutActuallyCollectingDoesNotReachAnAcquisitionObjective() {
        var goal = new AutonomyGoal("iron", Map.of("item:RAW_IRON", 1), 10);
        var action = new ActionModel("mine", Map.of(), Map.of(), Map.of("item:RAW_IRON", 1), 1);
        var empty = new PlanningState(Map.of());
        assertEquals(AutonomyFeedback.Outcome.NO_PROGRESS, AutonomyFeedback.assess(goal, action, empty, empty));
        assertEquals(AutonomyFeedback.Outcome.ACHIEVED, AutonomyFeedback.assess(goal, action, empty,
                new PlanningState(Map.of("item:RAW_IRON", 1))));
    }

    @Test void FurnaceInsertionIsProgressButNotAnIngotInTheInventory() {
        var goal = new AutonomyGoal("ingots", Map.of("item:IRON_INGOT", 8), 10);
        var action = new ActionModel("smelt", Map.of("item:RAW_IRON", 8), Map.of("item:RAW_IRON", 8),
                Map.of("pending:IRON_INGOT", 8), 1);
        var before = new PlanningState(Map.of("item:RAW_IRON", 8));
        var inserted = new PlanningState(Map.of("pending:IRON_INGOT", 8));
        assertEquals(AutonomyFeedback.Outcome.PROGRESSED, AutonomyFeedback.assess(goal, action, before, inserted));
        assertFalse(goal.achieved(inserted));
    }

    @Test void ResourceLossAloneDoesNotCountAsProgress() {
        var goal = new AutonomyGoal("tool", Map.of("tool", 1), 10);
        var action = new ActionModel("craft", Map.of("wood", 2), Map.of("wood", 2), Map.of("tool", 1), 1);
        assertEquals(AutonomyFeedback.Outcome.NO_PROGRESS, AutonomyFeedback.assess(goal, action,
                new PlanningState(Map.of("wood", 2)), new PlanningState(Map.of())));
    }

    @Test void aSmallerActualFurnaceBatchIsPartialProgressOnly() {
        var goal = new AutonomyGoal("iron", Map.of("item:IRON_INGOT", 8), 10);
        var action = new ActionModel("smelt", Map.of(), Map.of(), Map.of("pending:IRON_INGOT:8", 1), 1);
        assertEquals(AutonomyFeedback.Outcome.PROGRESSED, AutonomyFeedback.assess(goal, action,
                new PlanningState(Map.of()), new PlanningState(Map.of("pending:IRON_INGOT:3", 1))));
    }

    @Test void finishingWallsWithoutAllFixturesIsProgressRatherThanWholeGoalSuccess() {
        var goal = new AutonomyGoal("home", Map.of("shelter", 1), 10);
        var action = new ActionModel("build", Map.of(), Map.of(), Map.of("shelter", 1), 1);
        assertEquals(AutonomyFeedback.Outcome.PROGRESSED, AutonomyFeedback.assess(goal, action,
                new PlanningState(Map.of("remaining:shelter", 3)), new PlanningState(Map.of())));
    }

    @Test void placedPortalFrameIsProgressWhileActivationRemainsUnfinished() {
        var goal = new AutonomyGoal("portal", Map.of("world:portal", 1), 10);
        var action = new ActionModel("build", Map.of(), Map.of(), Map.of("world:portal", 1), 1);
        assertEquals(AutonomyFeedback.Outcome.PROGRESSED, AutonomyFeedback.assess(goal, action,
                new PlanningState(Map.of("stock:portal-frame", 2)), new PlanningState(Map.of("stock:portal-frame", 7))));
    }
}
