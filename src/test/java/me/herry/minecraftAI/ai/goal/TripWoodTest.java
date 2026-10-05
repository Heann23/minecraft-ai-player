package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.brain.GoalReasons;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 땅속으로 내려가기 전에 나무를 챙기는 규칙.
 * (고정 시드에서 판자를 화로 연료로 다 쓰고 내려갔다가, 돌 곡괭이가 부서졌을 때 새로 만들지 못하고
 * 나무를 구하러 밤의 지상으로 올라오다가 굴 안에서 멈췄다.)
 */
class TripWoodTest {
    private final GoalSystem goals = new GoalSystem();

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 돌 곡괭이까지 갖췄고 음식도 챙겼고, 이제 철을 찾아 내려갈 차례인 지상의 AI
    private static Situation readyToDescend() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        situation.need = Situation.Need.IRON;
        situation.ironNeeded = 3;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.hasFood = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        return situation;
    }

    @Test
    void gathersWoodBeforeGoingDown() {
        Situation situation = readyToDescend();
        situation.plankEquivalent = 3;
        assertTrue(GoalSystem.packsWoodForTrip(situation));
        // 아는 나무가 없으면 찾고, 있으면 벤다.
        assertEquals(GoalType.FIND_WOOD, select(situation));
        situation.knowsTree = true;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
        assertTrue(GoalReasons.explain(GoalType.COLLECT_WOOD, situation).contains("내려가기 전에"));
    }

    @Test
    void goesDownOnceItHasEnough() {
        Situation situation = readyToDescend();
        situation.knowsTree = true;
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        assertFalse(GoalSystem.packsWoodForTrip(situation));
        assertEquals(GoalType.FIND_IRON, select(situation));
    }

    // 이미 땅속에 있으면 나무 때문에 올라오지 않는다. 내려가기 전에만 챙긴다.
    @Test
    void doesNotComeUpForIt() {
        Situation situation = readyToDescend();
        situation.underground = true;
        situation.plankEquivalent = 0;
        assertFalse(GoalSystem.packsWoodForTrip(situation));
        assertEquals(GoalType.FIND_IRON, select(situation));
    }

    // 캐러 갈 철 광석을 이미 알고 있으면 찾아 내려가는 것이 아니므로 그대로 캐러 간다.
    @Test
    void knownOreIsMinedFirst() {
        Situation situation = readyToDescend();
        situation.knowsIron = true;
        situation.plankEquivalent = 0;
        assertFalse(GoalSystem.packsWoodForTrip(situation));
        assertEquals(GoalType.MINE_IRON, select(situation));
    }

    // 다이아몬드를 찾으러 내려갈 때도 마찬가지다.
    @Test
    void alsoBeforeLookingForDiamonds() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.DIAMOND_PICKAXE;
        situation.need = Situation.Need.DIAMOND;
        situation.diamondsNeeded = 3;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.canMineDiamond = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.plankEquivalent = 4;
        assertEquals(GoalType.FIND_WOOD, select(situation));
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        assertEquals(GoalType.FIND_DIAMOND, select(situation));
    }

    // 나무를 계속 구하지 못하면(나무가 없는 지형) 그 목표가 쉬는 동안 내려간다. 나무 때문에 영영 못 내려가지 않는다.
    @Test
    void descendsAnywayWhileTheWoodGoalRests() {
        Situation situation = readyToDescend();
        situation.plankEquivalent = 0;
        goals.cooldown(GoalType.FIND_WOOD, 0L, 200L);
        assertEquals(GoalType.FIND_IRON, select(situation));
    }
}
