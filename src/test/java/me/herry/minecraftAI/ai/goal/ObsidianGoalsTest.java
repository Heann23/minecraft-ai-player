package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.brain.GoalReasons;
import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.observation.ObservationFixture;
import me.herry.minecraftAI.ai.plan.BucketRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 흑요석을 모으는 목표. 물 양동이 다음 단계이고 네더 포탈의 재료다.
 */
class ObsidianGoalsTest {
    private final GoalSystem goals = new GoalSystem();

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 다이아몬드 곡괭이와 물 양동이를 갖춘 AI
    private static Situation needsObsidian() {
        Situation situation = new Situation();
        situation.stage = Stage.NETHER_ENTRY;
        situation.nextMilestone = Milestone.OBSIDIAN;
        situation.need = Situation.Need.OTHER;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.canMineDiamond = true;
        situation.canMineObsidian = true;
        situation.waterBucket = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        return situation;
    }

    @Test
    void goesToAKnownLavaLake() {
        Situation situation = needsObsidian();
        situation.knowsLava = true;
        assertEquals(GoalType.GATHER_OBSIDIAN, select(situation));
        assertTrue(GoalReasons.explain(GoalType.GATHER_OBSIDIAN, situation).contains("용암 호수로 가고"));
    }

    @Test
    void searchesUndergroundWhenNoLakeIsKnown() {
        Situation situation = needsObsidian();
        assertEquals(GoalType.GATHER_OBSIDIAN, select(situation));
        assertTrue(GoalReasons.explain(GoalType.GATHER_OBSIDIAN, situation).contains("찾고 있어요"));
    }

    // 물 없이는 흑요석을 만들 수 없고, 다이아몬드 곡괭이 없이는 캘 수 없다.
    @Test
    void needsWaterAndADiamondPickaxe() {
        Situation noWater = needsObsidian();
        noWater.knowsLava = true;
        noWater.waterBucket = false;
        assertNotEquals(GoalType.GATHER_OBSIDIAN, select(noWater));

        Situation noPickaxe = needsObsidian();
        noPickaxe.knowsLava = true;
        noPickaxe.canMineObsidian = false;
        assertNotEquals(GoalType.GATHER_OBSIDIAN, select(noPickaxe));
    }

    /**
     * 물을 부어 놓고 캐는 동안에는 물 양동이가 비어 있어서 다음 단계가 "물 양동이"로 보인다.
     * 그래도 부어 놓은 물을 도로 뜨러 가거나(붓고 뜨기를 끝없이 되풀이한다) 구멍에 떨어진 흑요석을 주우러 물살로 내려가지 않는다.
     */
    @Test
    void keepsMiningWhileTheWaterIsPouredOut() {
        Situation situation = needsObsidian();
        situation.nextMilestone = Milestone.WATER_BUCKET;
        situation.waterBucket = false;
        situation.emptyBucket = true;
        situation.knowsWater = true;
        situation.dropsNearby = true;
        situation.obsidianWork = true;
        assertEquals(GoalType.GATHER_OBSIDIAN, select(situation));
        assertTrue(GoalReasons.explain(GoalType.GATHER_OBSIDIAN, situation).contains("캐고 있어요"));

        // 받침에서 내려왔다면(싸움 등) 부어 둔 물부터 거둔다.
        situation.obsidianWork = false;
        situation.dropsNearby = false;
        assertEquals(GoalType.FILL_BUCKET, select(situation));
    }

    @Test
    void automationReachesObsidian() {
        assertTrue(Milestone.OBSIDIAN.isAutomated());
        assertFalse(Milestone.BLAZE_RODS.isAutomated());
    }

    @Test
    void mapsToAcquiringTenObsidian() {
        Goal goal = LegacyGoalAdapter.toGoal(GoalType.GATHER_OBSIDIAN);
        AcquireGoal acquire = assertInstanceOf(AcquireGoal.class, goal);
        assertEquals("OBSIDIAN", acquire.item());
        assertEquals(GoalType.GATHER_OBSIDIAN, LegacyGoalAdapter.toLegacy(goal));
        assertFalse(goal.isAchieved(new ObservationFixture().item("OBSIDIAN", Milestone.PORTAL_OBSIDIAN - 1).build()));
        assertTrue(goal.isAchieved(new ObservationFixture().item("OBSIDIAN", Milestone.PORTAL_OBSIDIAN).build()));
    }

    @Test
    void waterIsPouredOnlyOntoSolidGroundAndNeverInTheNether() {
        assertTrue(BucketRules.canPourInto(BlockClass.OPEN, BlockClass.SOLID));
        assertFalse(BucketRules.canPourInto(BlockClass.SOLID, BlockClass.SOLID));
        assertFalse(BucketRules.canPourInto(BlockClass.OPEN, BlockClass.OPEN));
        assertFalse(BucketRules.canPourInto(BlockClass.OPEN, BlockClass.DANGER));
        assertTrue(BucketRules.evaporates(true));
        assertFalse(BucketRules.evaporates(false));
    }
}
