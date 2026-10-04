package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 먹을 것, 위험, 전투처럼 살아남는 일과 관련된 목표 선택. (GoalSystemTest 에서 나눠 온 것이다.)
 */
class SurvivalGoalsTest {
    private final GoalSystem goals = new GoalSystem();

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 아무것도 없이 막 생성된 상태: 작업대를 만들어야 하는데 나무가 없다.
    private static Situation fresh() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.CRAFTING_TABLE;
        situation.need = Situation.Need.WOOD;
        return situation;
    }

    @Test
    void sharesFoodWithHungryAlly() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.allyWantsFood = true;
        situation.foodCount = 6;
        assertEquals(GoalType.SHARE_FOOD, select(situation));

        // 줄 것이 없으면 나서지 않는다.
        situation.foodCount = 1;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    // 역할 분담(따라다니기, REGROUP)은 없어졌다. 배고픈 AI 는 스스로 먹을 것을 구하러 가고,
    // 동료가 가져오는 중일 때만 하던 일을 계속한다.
    @Test
    void hungryAiForagesUnlessFoodIsComing() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.shouldEat = true;
        situation.food = 9;
        assertEquals(GoalType.FIND_FOOD, select(situation));

        situation.foodOnTheWay = true;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    // 식량 비축은 "식량 담당" 역할의 일이 아니라, 혼자서도 사냥감이 보이면 하는 일이다.
    @Test
    void stocksFoodWhenPreyIsInSight() {
        Situation situation = new Situation();
        situation.hasPickaxe = true;
        situation.preyNearby = true;
        assertEquals(GoalType.STOCK_FOOD, select(situation));

        situation.foodCount = GoalSystem.FOOD_STOCK;
        assertEquals(GoalType.EXPLORE, select(situation));

        // 사냥감이 보이지 않으면 배가 고프지 않은 한 찾아다니지 않는다.
        situation.foodCount = 0;
        situation.preyNearby = false;
        assertEquals(GoalType.EXPLORE, select(situation));

        // 첫 도구를 만들기 전에는 사냥보다 도구가 먼저다.
        situation.preyNearby = true;
        situation.hasPickaxe = false;
        assertEquals(GoalType.EXPLORE, select(situation));
    }

    @Test
    void lavaBeatsEverything() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.combat = CombatSystem.Decision.FIGHT;
        situation.starving = true;
        situation.hasFood = true;
        situation.inLava = true;
        assertEquals(GoalType.ESCAPE_DANGER, select(situation));
    }

    @Test
    void suffocationIsAnEmergency() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.suffocating = true;
        assertEquals(GoalType.ESCAPE_DANGER, select(situation));
    }

    @Test
    void helpsAllyUnlessHurtOrFleeing() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.allyNeedsHelp = true;
        assertEquals(GoalType.ASSIST_ALLY, select(situation));

        // 자기 앞의 몬스터가 먼저다.
        situation.combat = CombatSystem.Decision.FIGHT;
        assertEquals(GoalType.FIGHT_HOSTILE, select(situation));

        situation.combat = CombatSystem.Decision.FLEE;
        assertEquals(GoalType.ESCAPE_DANGER, select(situation));

        situation.combat = CombatSystem.Decision.NONE;
        situation.healthState = SurvivalSystem.HealthState.LOW;
        situation.canRegenerate = false;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    @Test
    void fleeDecisionEscapesAndFightDecisionFights() {
        Situation situation = fresh();
        situation.combat = CombatSystem.Decision.FLEE;
        assertEquals(GoalType.ESCAPE_DANGER, select(situation));

        situation.combat = CombatSystem.Decision.FIGHT;
        assertEquals(GoalType.FIGHT_HOSTILE, select(situation));
    }

    @Test
    void lowHealthRecoversInsteadOfWorking() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.health = 6.0;
        situation.healthState = SurvivalSystem.HealthState.LOW;
        situation.hasFood = true;
        situation.food = 15;
        assertEquals(GoalType.SURVIVE, select(situation));
    }

    @Test
    void eatsWhenHungryAndHuntsWhenStarving() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.shouldEat = true;
        situation.hasFood = true;
        assertEquals(GoalType.FIND_FOOD, select(situation));

        // 음식도 사냥감도 없으면 배가 조금 고픈 정도로는 하던 일을 계속한다.
        situation.hasFood = false;
        situation.food = 13;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));

        situation.starving = true;
        assertEquals(GoalType.FIND_FOOD, select(situation));
    }

    // 회귀: 음식 없이 철을 찾으러 내려갔다가, 굶주린 채 밤에 사냥하러 올라와서 체력이 4 까지 떨어졌다.
    @Test
    void gathersFoodBeforeGoingUndergroundForOre() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        situation.need = Situation.Need.IRON;
        situation.ironNeeded = 3;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        assertEquals(GoalType.STOCK_FOOD, select(situation));

        // 음식을 챙겼으면 내려간다.
        situation.foodCount = GoalSystem.TRIP_FOOD;
        assertEquals(GoalType.FIND_IRON, select(situation));

        // 이미 땅속에 있으면 올라가서 사냥하지 않고 하던 일을 한다.
        situation.foodCount = 0;
        situation.underground = true;
        assertEquals(GoalType.FIND_IRON, select(situation));

        // 한참 찾아도 사냥감이 없었으면 더 찾지 않고 내려간다.
        situation.underground = false;
        situation.foodSearchExhausted = true;
        assertEquals(GoalType.FIND_IRON, select(situation));
    }
}
