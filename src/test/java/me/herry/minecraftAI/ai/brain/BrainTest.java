package me.herry.minecraftAI.ai.brain;

import me.herry.minecraftAI.ai.comm.Intent;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainTest {
    // --- 실패 회복 ---

    @Test
    void goalRestsAfterRepeatedFailures() {
        RecoveryTracker recovery = new RecoveryTracker();

        assertEquals(0L, recovery.onPlanFailed(GoalType.MINE_IRON));
        assertEquals(0L, recovery.onPlanFailed(GoalType.MINE_IRON));
        assertEquals(RecoveryTracker.BASE_REST_TICKS, recovery.onPlanFailed(GoalType.MINE_IRON));
        // 쉬고 나면 연속 실패 횟수는 처음부터 다시 센다.
        assertEquals(0, recovery.failureStreak(GoalType.MINE_IRON));
    }

    // 쉬고 돌아와서도 또 실패하면 더 오래 쉰다. 같은 일을 끝없이 되풀이하지 않게 하는 장치다.
    @Test
    void restGrowsWhenTheGoalKeepsFailing() {
        RecoveryTracker recovery = new RecoveryTracker();
        long[] expected = {200L, 400L, 800L, 1600L, 2400L, 2400L};
        for (long rest : expected) {
            recovery.onPlanFailed(GoalType.BUILD_SHELTER);
            recovery.onPlanFailed(GoalType.BUILD_SHELTER);
            assertEquals(rest, recovery.onPlanFailed(GoalType.BUILD_SHELTER));
        }
        assertEquals(expected.length, recovery.restCount(GoalType.BUILD_SHELTER));
    }

    @Test
    void successResetsEverything() {
        RecoveryTracker recovery = new RecoveryTracker();
        for (int i = 0; i < 6; i++) recovery.onPlanFailed(GoalType.MINE_STONE);
        recovery.onPlanSucceeded(GoalType.MINE_STONE);

        assertEquals(0, recovery.restCount(GoalType.MINE_STONE));
        assertEquals(0L, recovery.onPlanFailed(GoalType.MINE_STONE));
        assertEquals(0L, recovery.onPlanFailed(GoalType.MINE_STONE));
        assertEquals(RecoveryTracker.BASE_REST_TICKS, recovery.onPlanFailed(GoalType.MINE_STONE));
    }

    @Test
    void switchingGoalsKeepsTheRestHistory() {
        RecoveryTracker recovery = new RecoveryTracker();
        for (int i = 0; i < 3; i++) recovery.onPlanFailed(GoalType.FIND_IRON);
        recovery.onPlanFailed(GoalType.FIND_IRON);
        recovery.onGoalSwitched(GoalType.FIND_IRON);

        // 다른 목표로 갔다가 돌아오면 연속 실패는 0 부터지만, 쉰 이력은 남아서 다음에는 더 오래 쉰다.
        assertEquals(0, recovery.failureStreak(GoalType.FIND_IRON));
        recovery.onPlanFailed(GoalType.FIND_IRON);
        recovery.onPlanFailed(GoalType.FIND_IRON);
        assertEquals(400L, recovery.onPlanFailed(GoalType.FIND_IRON));
        // 다른 목표와는 따로 센다.
        assertEquals(0L, recovery.onPlanFailed(GoalType.EXPLORE));
    }

    // --- 판단 기록과 설명 ---

    private static Situation needingIron() {
        Situation situation = new Situation();
        situation.stage = Stage.IRON_AGE;
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        situation.need = Situation.Need.IRON;
        situation.ironNeeded = 3;
        situation.rawIron = 1;
        return situation;
    }

    @Test
    void reasonsMentionWhatIsMissing() {
        Situation situation = needingIron();
        String reason = GoalReasons.explain(GoalType.FIND_IRON, situation);

        assertTrue(reason.contains("철 곡괭이"), reason);
        assertTrue(reason.contains("3개"), reason);
        assertTrue(reason.contains("1개"), reason);
    }

    // 회귀: 작업대를 만드는 이유를 "작업대가 있어야 작업대를 만들 수 있어서요" 라고 말했다.
    @Test
    void explainsWhyItMakesAWorkbench() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.CRAFTING_TABLE;
        String reason = GoalReasons.explain(GoalType.CRAFT_WORKBENCH, situation);

        assertFalse(reason.contains("작업대를 만들 수"), reason);
        assertTrue(reason.contains("작업대"), reason);
    }

    @Test
    void everyGoalHasAReason() {
        Situation situation = needingIron();
        for (GoalType goal : GoalType.values()) {
            String reason = GoalReasons.explain(goal, situation);
            assertNotNull(reason);
            assertFalse(reason.isBlank(), goal.name());
        }
        // 다음에 이룰 것이 없을 때(클리어 뒤)도 설명할 수 있어야 한다.
        situation.nextMilestone = null;
        for (GoalType goal : GoalType.values()) assertFalse(GoalReasons.explain(goal, situation).isBlank(), goal.name());
    }

    // 스스로 할 수 없는 단계에 이르면 그 사실을 숨기지 않고 말한다.
    @Test
    void explainsWhenTheNextStepIsBeyondItsSkills() {
        Situation situation = new Situation();
        situation.stage = Stage.NETHER_ENTRY;
        situation.nextMilestone = Milestone.OBSIDIAN;
        String reason = GoalReasons.explain(GoalType.EXPLORE, situation);

        assertTrue(reason.contains("흑요석"), reason);
        assertTrue(reason.contains("할 줄 모르는"), reason);
    }

    // 땅속에서 밤을 나느라 기다리는 중이면, 할 일이 없어서가 아니라 아침을 기다리는 것임을 말한다.
    @Test
    void explainsWaitingBelowGroundForMorning() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_SWORD;
        situation.need = Situation.Need.WOOD;
        situation.underground = true;
        situation.surfaceTooLate = true;

        for (GoalType goal : List.of(GoalType.EXPLORE, GoalType.IDLE)) {
            String reason = GoalReasons.explain(goal, situation);
            assertTrue(reason.contains("아침"), reason);
            assertTrue(reason.contains("철 검"), reason);
        }

        // 낮에는 평소의 설명을 한다.
        situation.surfaceTooLate = false;
        assertFalse(GoalReasons.explain(GoalType.EXPLORE, situation).contains("아침"));

        // 밤이어도 모자란 것이 땅속에서 구할 수 있는 것이면 아침을 기다리는 것이 아니다.
        situation.surfaceTooLate = true;
        situation.need = Situation.Need.IRON;
        assertFalse(GoalReasons.explain(GoalType.EXPLORE, situation).contains("아침"));
    }

    @Test
    void explanationListsEveryLevelOfGoal() {
        DecisionTrace trace = new DecisionTrace(100L, GoalType.FIND_IRON, 150.0, DecisionTrace.Origin.AUTONOMOUS,
                Stage.IRON_AGE, Milestone.IRON_PICKAXE,
                List.of(new DecisionTrace.Candidate(GoalType.FIND_IRON, 150.0, false), new DecisionTrace.Candidate(GoalType.EXPLORE, 50.0, false)),
                "철이 부족해서요", GoalType.CRAFT_TOOL);

        List<String> lines = DecisionExplainer.describe(trace, "BreakBlock");
        assertEquals(6, lines.size());
        assertTrue(lines.get(0).contains(DecisionExplainer.FINAL_GOAL));
        assertTrue(lines.get(1).contains(Stage.IRON_AGE.label()));
        assertTrue(lines.get(2).contains("철 곡괭이"));
        assertTrue(lines.get(3).contains("철을 찾는"));
        assertTrue(lines.get(4).contains("BreakBlock"));
        assertTrue(lines.get(5).contains("철이 부족해서요"));

        List<String> candidates = DecisionExplainer.candidates(trace);
        assertEquals(2, candidates.size());
        assertTrue(candidates.get(0).contains("선택"));
        assertFalse(candidates.get(1).contains("선택"));
    }

    @Test
    void explanationWithoutAnyDecision() {
        assertEquals(1, DecisionExplainer.describe(null, "None").size());
        assertFalse(DecisionExplainer.brief(null).isBlank());
        assertTrue(DecisionExplainer.candidates(null).isEmpty());
    }

    @Test
    void decisionLogIsBounded() {
        DecisionLog log = new DecisionLog();
        assertNull(log.current());
        for (int i = 0; i < 50; i++) {
            log.record(new DecisionTrace(i, GoalType.EXPLORE, 50.0, DecisionTrace.Origin.AUTONOMOUS, Stage.EARLY_SURVIVAL, null, List.of(), "r" + i, null));
            log.noteFailure(i, GoalType.EXPLORE, "MoveTo", "stuck");
            log.noteGiveUp(i, GoalType.EXPLORE, 200L, "stuck");
        }

        assertEquals(16, log.history().size());
        assertEquals(49L, log.current().tick());
        assertEquals(12, log.getFailures().size());
        assertEquals(49L, log.lastFailure().tick());
        assertEquals(8, log.getGiveUps().size());
    }

    // --- 사람이 부탁한 일 ---

    @Test
    void directivePicksTheGoalFromWhatIsKnown() {
        Situation situation = new Situation();
        Directive iron = Directive.gather(Intent.Subject.IRON, "Steve", 0L);
        assertEquals(GoalType.FIND_IRON, iron.goalFor(situation));
        situation.knowsIron = true;
        assertEquals(GoalType.MINE_IRON, iron.goalFor(situation));

        Directive wood = Directive.gather(Intent.Subject.WOOD, "Steve", 0L);
        assertEquals(GoalType.FIND_WOOD, wood.goalFor(situation));
        situation.knowsTree = true;
        assertEquals(GoalType.COLLECT_WOOD, wood.goalFor(situation));

        Directive diamond = Directive.gather(Intent.Subject.DIAMOND, "Steve", 0L);
        assertEquals(GoalType.FIND_DIAMOND, diamond.goalFor(situation));
        situation.knowsDiamond = true;
        assertEquals(GoalType.MINE_DIAMOND, diamond.goalFor(situation));

        assertEquals(GoalType.RETURN_HOME, Directive.goHome("Steve", 0L).goalFor(situation));
    }

    @Test
    void directiveIsRefusedWhenItCannotBeDone() {
        Situation situation = new Situation();
        assertNotNull(Directive.goHome("Steve", 0L).rejection(situation));
        assertNotNull(Directive.gather(Intent.Subject.IRON, "Steve", 0L).rejection(situation));
        assertNotNull(Directive.gather(Intent.Subject.DIAMOND, "Steve", 0L).rejection(situation));
        // 나무는 맨손으로도 구할 수 있다.
        assertNull(Directive.gather(Intent.Subject.WOOD, "Steve", 0L).rejection(situation));

        situation.homeKnown = true;
        situation.canMineIron = true;
        assertNull(Directive.goHome("Steve", 0L).rejection(situation));
        assertNull(Directive.gather(Intent.Subject.IRON, "Steve", 0L).rejection(situation));
    }

    @Test
    void directiveEndsOnArrivalOrTimeout() {
        Situation situation = new Situation();
        situation.homeKnown = true;
        situation.homeDistance = 30.0;
        Directive home = Directive.goHome("Steve", 100L);
        assertFalse(home.isDone(situation));
        assertFalse(home.isExpired(100L + Directive.GO_HOME_TICKS - 1));
        assertTrue(home.isExpired(100L + Directive.GO_HOME_TICKS));

        situation.homeDistance = 2.0;
        assertTrue(home.isDone(situation));

        // 자원 모으기는 시간이 다 될 때까지 계속한다.
        Directive gather = Directive.gather(Intent.Subject.WOOD, "Steve", 0L);
        assertFalse(gather.isDone(situation));
        assertTrue(gather.isExpired(Directive.GATHER_TICKS));
        assertTrue(gather.describe().contains("Steve"));
    }
}
