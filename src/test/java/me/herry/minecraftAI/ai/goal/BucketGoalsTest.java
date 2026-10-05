package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.brain.GoalReasons;
import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.observation.ObservationFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 양동이에 물을 뜨는 목표. 부싯돌과 부시 다음 단계이고, 흑요석을 만드는 데 쓴다.
 */
class BucketGoalsTest {
    private final GoalSystem goals = new GoalSystem();

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 부싯돌과 부시까지 갖췄고 빈 양동이를 가진 AI
    private static Situation needsWater() {
        Situation situation = new Situation();
        situation.stage = Stage.NETHER_ENTRY;
        situation.nextMilestone = Milestone.WATER_BUCKET;
        situation.need = Situation.Need.OTHER;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.canMineDiamond = true;
        situation.emptyBucket = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        return situation;
    }

    @Test
    void goesToKnownWater() {
        Situation situation = needsWater();
        situation.knowsWater = true;
        assertEquals(GoalType.FILL_BUCKET, select(situation));
        assertTrue(GoalReasons.explain(GoalType.FILL_BUCKET, situation).contains("물을 뜨러"));
    }

    @Test
    void searchesWhenNoWaterIsKnown() {
        Situation situation = needsWater();
        assertEquals(GoalType.FILL_BUCKET, select(situation));
        assertTrue(GoalReasons.explain(GoalType.FILL_BUCKET, situation).contains("찾고 있어요"));
    }

    // 양동이가 없으면 뜰 수 없다. 양동이를 만드는 것은 그 앞 단계(BUCKET)의 일이다.
    @Test
    void doesNothingWithoutAnEmptyBucket() {
        Situation situation = needsWater();
        situation.emptyBucket = false;
        situation.knowsWater = true;
        assertNotEquals(GoalType.FILL_BUCKET, select(situation));
    }

    @Test
    void ignoresWaterWhenItIsNotTheNextStep() {
        Situation situation = needsWater();
        situation.nextMilestone = Milestone.OBSIDIAN;
        situation.knowsWater = true;
        assertNotEquals(GoalType.FILL_BUCKET, select(situation));
    }

    // 땅속에서 밤을 나는 동안에는 물을 찾으러 지상에 올라가지 않는다. 바로 옆에 아는 물이 있으면 뜬다.
    @Test
    void doesNotSearchWhileStayingBelowAtNight() {
        Situation situation = needsWater();
        situation.underground = true;
        situation.surfaceTooLate = true;
        assertNotEquals(GoalType.FILL_BUCKET, select(situation));
        situation.knowsWater = true;
        assertEquals(GoalType.FILL_BUCKET, select(situation));
    }

    @Test
    void automationReachesTheWaterBucket() {
        assertTrue(Milestone.WATER_BUCKET.isAutomated());
        assertFalse(Milestone.BLAZE_RODS.isAutomated());
    }

    @Test
    void mapsToAcquiringOneWaterBucket() {
        Goal goal = LegacyGoalAdapter.toGoal(GoalType.FILL_BUCKET);
        AcquireGoal acquire = assertInstanceOf(AcquireGoal.class, goal);
        assertEquals("WATER_BUCKET", acquire.item());
        assertEquals(GoalType.FILL_BUCKET, LegacyGoalAdapter.toLegacy(goal));
        assertFalse(goal.isAchieved(new ObservationFixture().build()));
        assertTrue(goal.isAchieved(new ObservationFixture().item("WATER_BUCKET", 1).build()));
    }
}
