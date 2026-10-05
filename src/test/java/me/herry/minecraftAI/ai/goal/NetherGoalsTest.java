package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.brain.GoalReasons;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.goal.model.ReachGoal;
import me.herry.minecraftAI.ai.plan.SurfaceRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 포탈로 네더를 드나드는 목표와, 천장이 있는 차원에서의 지형 판단.
 */
class NetherGoalsTest {
    private final GoalSystem goals = new GoalSystem();

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 네더 포탈까지 만들었고, 다음 단계는 네더에서 블레이즈 막대를 구하는 일이다.
    private static Situation portalBuilt() {
        Situation situation = new Situation();
        situation.stage = Stage.NETHER;
        situation.nextMilestone = Milestone.BLAZE_RODS;
        situation.need = Situation.Need.OTHER;
        situation.hasPickaxe = true;
        situation.knowsPortal = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        return situation;
    }

    /**
     * 네더에서 하는 일을 아직 스스로 하지 못하는 동안에는 스스로 들어가지 않는다.
     * 들어가 봐야 할 수 있는 일이 없고, 가진 것을 다 잃을 수 있다. 사람이 시키면 들어간다 (/ai goal ENTER_NETHER).
     */
    @Test
    void doesNotEnterTheNetherOnItsOwnYet() {
        assertFalse(Milestone.BLAZE_RODS.isAutomated(), "이 단계가 자동이 되면 이 테스트를 고친다");
        assertFalse(portalBuilt().netherWorkReady());
        assertNotEquals(GoalType.ENTER_NETHER, select(portalBuilt()));
    }

    // 네더에 있는데 거기서 할 수 있는 일이 없으면 돌아온다. 평소의 일(나무, 돌, 줍기)보다 먼저다.
    @Test
    void comesBackWhenThereIsNothingToDoInTheNether() {
        Situation situation = portalBuilt();
        situation.inNether = true;
        situation.dropsNearby = true;
        assertEquals(GoalType.LEAVE_NETHER, select(situation));
        assertTrue(GoalReasons.explain(GoalType.LEAVE_NETHER, situation).contains("돌아가려고요"));
    }

    // 돌아갈 포탈을 모르면 그 목표는 고르지 않는다 (계획을 세울 수 없다).
    @Test
    void needsAKnownPortalToComeBack() {
        Situation situation = portalBuilt();
        situation.inNether = true;
        situation.knowsPortal = false;
        assertNotEquals(GoalType.LEAVE_NETHER, select(situation));
    }

    @Test
    void doesNotLeaveFromTheOverworld() {
        assertNotEquals(GoalType.LEAVE_NETHER, select(portalBuilt()));
    }

    @Test
    void mapsToReachingADimension() {
        Goal enter = LegacyGoalAdapter.toGoal(GoalType.ENTER_NETHER);
        assertEquals(ReachGoal.Place.NETHER, assertInstanceOf(ReachGoal.class, enter).place());
        assertEquals(GoalType.ENTER_NETHER, LegacyGoalAdapter.toLegacy(enter));
        Goal leave = LegacyGoalAdapter.toGoal(GoalType.LEAVE_NETHER);
        assertEquals(ReachGoal.Place.OVERWORLD, assertInstanceOf(ReachGoal.class, leave).place());
        assertEquals(GoalType.LEAVE_NETHER, LegacyGoalAdapter.toLegacy(leave));
    }

    // 오버월드: 머리 위로 땅이 두꺼우면 땅속, 가장 높은 블록 위에 서 있으면 하늘 아래다.
    @Test
    void overworldUsesTheHeightOfTheGround() {
        assertTrue(SurfaceRules.isDeepUnderground(false, 70, 40, 6));
        assertFalse(SurfaceRules.isDeepUnderground(false, 70, 66, 6));
        assertTrue(SurfaceRules.isUnderOpenSky(false, 70, 70));
        assertFalse(SurfaceRules.isUnderOpenSky(false, 70, 60));
        assertTrue(SurfaceRules.isHigherGround(false, 70, 60, 8));
        assertFalse(SurfaceRules.isHigherGround(false, 70, 65, 8));
    }

    /**
     * 네더: 가장 높은 블록은 천장의 기반암(y 127)이다. 그것을 지표면으로 보면 어디서든 "깊은 땅속"이 되어
     * 천장까지 계단을 파 올라가게 된다. 천장이 있는 차원에는 올라갈 지상이 없다.
     */
    @Test
    void netherHasNoSurfaceToClimbTo() {
        assertFalse(SurfaceRules.isDeepUnderground(true, 128, 40, 6));
        assertTrue(SurfaceRules.isUnderOpenSky(true, 128, 40));
        assertFalse(SurfaceRules.isHigherGround(true, 128, 40, 1));
    }
}
