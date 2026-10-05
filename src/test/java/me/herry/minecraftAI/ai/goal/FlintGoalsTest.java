package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.brain.GoalReasons;
import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.observation.ObservationFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 부싯돌을 구하는 목표. 다이아몬드 곡괭이 다음 단계(부싯돌과 부시)의 재료다.
 */
class FlintGoalsTest {
    private final GoalSystem goals = new GoalSystem();

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 다이아몬드 곡괭이까지 갖췄고 철 주괴도 있어서, 부싯돌만 있으면 부싯돌과 부시를 만들 수 있는 AI
    private static Situation needsFlint() {
        Situation situation = new Situation();
        situation.stage = Stage.NETHER_ENTRY;
        situation.nextMilestone = Milestone.FLINT_AND_STEEL;
        situation.need = Situation.Need.FLINT;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.canMineDiamond = true;
        situation.ironIngots = 1;
        situation.ironNeeded = 1;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        return situation;
    }

    @Test
    void minesKnownGravel() {
        Situation situation = needsFlint();
        situation.knowsGravel = true;
        assertEquals(GoalType.GATHER_FLINT, select(situation));
        assertTrue(GoalReasons.explain(GoalType.GATHER_FLINT, situation).contains("자갈을 캐고"));
    }

    // 아는 자갈이 없어도 가진 자갈이 있으면 놓고 다시 캐면 된다.
    @Test
    void usesGravelItCarries() {
        Situation situation = needsFlint();
        situation.gravel = 5;
        assertEquals(GoalType.GATHER_FLINT, select(situation));
    }

    // 자갈이 하나도 없으면 찾으러 다닌다. 다른 급한 일(사냥감이 보일 때 음식 모으기)보다는 나중이다.
    @Test
    void searchesWhenThereIsNoGravel() {
        Situation situation = needsFlint();
        assertEquals(GoalType.GATHER_FLINT, select(situation));
        assertTrue(GoalReasons.explain(GoalType.GATHER_FLINT, situation).contains("자갈이 없어서"));

        situation.preyNearby = true;
        situation.foodCount = 2;
        assertEquals(GoalType.STOCK_FOOD, select(situation));
    }

    // 부싯돌이 필요하지 않으면 자갈이 보여도 캐지 않는다.
    @Test
    void ignoresGravelWhenFlintIsNotNeeded() {
        Situation situation = needsFlint();
        situation.need = Situation.Need.NONE;
        situation.knowsGravel = true;
        situation.gravel = 8;
        assertEquals(GoalType.CRAFT_TOOL, select(situation));
    }

    // 땅속에서 밤을 나는 동안에는 자갈을 찾으러 올라가지 않는다. 가진 자갈로는 그 자리에서 한다.
    @Test
    void doesNotSurfaceAtNightToSearch() {
        Situation situation = needsFlint();
        situation.underground = true;
        situation.surfaceTooLate = true;
        assertFalse(select(situation) == GoalType.GATHER_FLINT);
        situation.gravel = 3;
        assertEquals(GoalType.GATHER_FLINT, select(situation));
    }

    // 부싯돌과 부시까지는 스스로 할 수 있는 단계다. 그다음(흑요석)은 아직 아니다.
    @Test
    void flintAndSteelIsWithinTheAutomatedRange() {
        assertTrue(Milestone.FLINT_AND_STEEL.isAutomated());
        assertFalse(Milestone.BLAZE_RODS.isAutomated());
    }

    // 범용 목표로는 "부싯돌 1개를 가진다"이고, 가지면 이룬 것이다.
    @Test
    void mapsToAcquiringOneFlint() {
        Goal goal = LegacyGoalAdapter.toGoal(GoalType.GATHER_FLINT);
        AcquireGoal acquire = assertInstanceOf(AcquireGoal.class, goal);
        assertEquals("FLINT", acquire.item());
        assertEquals(1, acquire.count());
        assertFalse(goal.isAchieved(new ObservationFixture().build()));
        assertTrue(goal.isAchieved(new ObservationFixture().item("FLINT", 1).build()));
    }
}
