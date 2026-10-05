package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.AbstractAction;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.ActionStatus;
import me.herry.minecraftAI.ai.goal.GoalType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanAndActionTest {

    static class TestAction extends AbstractAction {
        boolean started;
        int ticks;
        boolean ended;

        TestAction(String name, int timeout) {
            super(name, timeout);
        }

        @Override
        protected void onStart(AIPlayer ai) {
            started = true;
        }

        @Override
        protected void onTick(AIPlayer ai) {
            ticks++;
        }

        @Override
        protected void onEnd(AIPlayer ai) {
            ended = true;
        }

        void doSucceed() {
            succeed();
        }

        void doFail(String reason) {
            fail(reason);
        }
    }

    @Test
    void testActionLifecycleSuccess() {
        TestAction action = new TestAction("Test1", 10);
        assertEquals(ActionStatus.READY, action.getStatus());
        assertEquals("Test1", action.getName());
        assertEquals("", action.getFailReason());

        // First update triggers onStart and first onTick
        action.update(null);
        assertTrue(action.started);
        assertEquals(1, action.ticks);
        assertEquals(ActionStatus.RUNNING, action.getStatus());

        action.doSucceed();
        assertEquals(ActionStatus.SUCCESS, action.getStatus());
        // next update runs onEnd
        action.update(null);
        assertTrue(action.ended);
    }

    @Test
    void testActionTimeout() {
        TestAction action = new TestAction("TimeoutAction", 2);
        action.update(null); // elapsed=1
        assertEquals(ActionStatus.RUNNING, action.getStatus());
        action.update(null); // elapsed=2
        assertEquals(ActionStatus.RUNNING, action.getStatus());
        action.update(null); // elapsed=3 > 2 -> fail("timeout")
        assertEquals(ActionStatus.FAILED, action.getStatus());
        assertEquals("timeout", action.getFailReason());
        assertTrue(action.ended);
    }

    @Test
    void testActionCancel() {
        TestAction action = new TestAction("CancelAction", 100);
        action.update(null);
        assertEquals(ActionStatus.RUNNING, action.getStatus());
        action.cancel(null);
        assertEquals(ActionStatus.FAILED, action.getStatus());
        assertEquals("cancelled", action.getFailReason());
        assertTrue(action.ended);
    }

    @Test
    void testPlanLifecycleAndProgress() {
        TestAction a1 = new TestAction("Step1", 10);
        TestAction a2 = new TestAction("Step2", 10);
        Plan plan = new Plan(GoalType.CRAFT_TOOL, List.of(a1, a2));

        assertEquals(GoalType.CRAFT_TOOL, plan.getGoal());
        assertFalse(plan.isFinished());
        assertSame(a1, plan.current());
        assertEquals("Step1 -> Step2", plan.describe());
        assertEquals("[Step1] -> Step2", plan.describeProgress());

        plan.advance();
        assertSame(a2, plan.current());
        assertFalse(plan.isFinished());
        assertEquals("Step1 -> [Step2]", plan.describeProgress());

        plan.advance();
        assertNull(plan.current());
        assertTrue(plan.isFinished());
        assertEquals("Step1 -> Step2", plan.describeProgress());
    }

    @Test
    void testPlanCancelCancelsCurrentAction() {
        TestAction a1 = new TestAction("Step1", 10);
        TestAction a2 = new TestAction("Step2", 10);
        Plan plan = new Plan(GoalType.MINE_STONE, List.of(a1, a2));

        a1.update(null);
        assertEquals(ActionStatus.RUNNING, a1.getStatus());

        plan.cancel(null);
        assertEquals(ActionStatus.FAILED, a1.getStatus());
        assertEquals("cancelled", a1.getFailReason());
        assertTrue(plan.isFinished());
        assertNull(plan.current());
    }

    @Test
    void testPlannerCustomRegistration() {
        Planner planner = new Planner();
        TestAction action = new TestAction("CustomAction", 50);

        planner.register(GoalType.EXPLORE, ai -> List.of(action));
        List<Action> actions = planner.plan(GoalType.EXPLORE, null);

        assertEquals(1, actions.size());
        assertSame(action, actions.get(0));
    }
}
