package me.herry.minecraftAI.ai.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 화로에 넣어 두고 다른 일을 하는 동안의 목표 선택.
 */
class FurnaceGoalsTest {
    private final GoalSystem goals = new GoalSystem();

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 철 곡괭이에 쓸 철을 화로에 넣어 둔 채로, 더 캘 철을 찾고 있다.
    private static Situation smelting() {
        Situation situation = new Situation();
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        situation.need = Situation.Need.IRON;
        situation.ironNeeded = 3;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.furnaceBusy = true;
        situation.furnaceDistance = 5.0;
        // 12개를 넣은 직후다 (2분).
        situation.furnaceTicksLeft = 2400;
        return situation;
    }

    // 사용자가 본 문제: 다 구워질 때까지 화로 앞에 서서 기다렸다.
    @Test
    void doesOtherWorkNearbyWhileSmelting() {
        Situation situation = smelting();
        assertEquals(0.0, goals.score(GoalType.TEND_FURNACE, situation));
        assertEquals(GoalType.FIND_IRON, select(situation));
    }

    @Test
    void comesBackWhenItStraysTooFar() {
        Situation situation = smelting();
        situation.furnaceDistance = GoalSystem.FURNACE_LEASH + 1.0;
        assertEquals(GoalType.TEND_FURNACE, select(situation));

        // 눈앞의 광맥은 마저 캐고 돌아간다.
        situation.ironVeinNearby = true;
        assertEquals(GoalType.MINE_IRON, select(situation));
    }

    // 경계 안에 들어서자마자 다시 떠나면 경계선에서 왔다 갔다 하기만 한다. 돌아오기 시작했으면 화로 가까이까지 온다.
    @Test
    void keepsComingBackUntilItIsClose() {
        Situation situation = smelting();
        situation.currentGoal = GoalType.TEND_FURNACE;
        situation.furnaceDistance = GoalSystem.FURNACE_LEASH / 2.0 + 1.0;
        assertEquals(GoalType.TEND_FURNACE, select(situation));

        // 가까이 왔고 시간이 많이 남았으면 다시 다른 일을 한다.
        situation.furnaceDistance = 2.0;
        assertEquals(GoalType.FIND_IRON, select(situation));
    }

    // 남은 시간이 줄수록 화로에서 멀어져도 되는 거리도 준다. 다 구워질 때쯤에는 화로에 돌아와 있어야 한다.
    @Test
    void headsBackInTimeToBeThereWhenItIsDone() {
        assertEquals(GoalSystem.FURNACE_LEASH, GoalSystem.furnaceLeash(2400));
        assertEquals(36.0, GoalSystem.furnaceLeash(240));
        assertEquals(GoalSystem.FURNACE_NEAR, GoalSystem.furnaceLeash(0));

        Situation situation = smelting();
        situation.furnaceTicksLeft = 240;
        situation.furnaceDistance = 30.0;
        assertEquals(GoalType.FIND_IRON, select(situation));
        situation.furnaceDistance = 40.0;
        assertEquals(GoalType.TEND_FURNACE, select(situation));
    }

    // 곧 다 구워지면 다른 일을 새로 시작하지 않고 곁에서 기다린다.
    @Test
    void waitsByTheFurnaceWhenItIsAlmostDone() {
        Situation situation = smelting();
        situation.furnaceTicksLeft = GoalSystem.FURNACE_WAIT_TICKS;
        situation.furnaceDistance = 2.0;
        assertEquals(GoalType.TEND_FURNACE, select(situation));
    }

    @Test
    void collectsAsSoonAsItIsDone() {
        Situation situation = smelting();
        situation.furnaceDone = true;
        situation.ironVeinNearby = true;
        assertEquals(GoalType.TEND_FURNACE, select(situation));
    }

    // 화로의 칸은 하나씩이라, 넣어 둔 것을 꺼내기 전에는 더 넣지 않는다.
    @Test
    void doesNotLoadAFurnaceThatIsStillBusy() {
        Situation situation = smelting();
        situation.rawIron = 3;
        situation.hasFuel = true;
        situation.canCook = true;
        situation.rawFood = GoalSystem.COOK_MIN;
        assertEquals(0.0, goals.score(GoalType.SMELT_IRON, situation));
        assertEquals(0.0, goals.score(GoalType.COOK_FOOD, situation));

        situation.furnaceBusy = false;
        assertEquals(GoalType.COOK_FOOD, select(situation));
    }

    // 배가 고파서 구운 것이면 다 익기를 기다렸다가 먹는다. 기다리는 사이에 가방의 날고기를 먹지 않는다.
    @Test
    void waitsForTheFoodItIsCookingWhenHungry() {
        Situation situation = smelting();
        situation.furnaceCooksFood = true;
        situation.shouldEat = true;
        situation.hasFood = true;
        situation.rawFood = 2;
        situation.readyFood = 0;
        assertEquals(GoalType.TEND_FURNACE, select(situation));

        // 굶주렸으면 날것이라도 먹는다.
        situation.starving = true;
        assertEquals(GoalType.FIND_FOOD, select(situation));
    }

    // 땅속에서 밤을 나는 동안에는 지상에 있는 화로를 가지러 올라가지 않는다.
    @Test
    void leavesASurfaceFurnaceUntilMorningWhenBelowAtNight() {
        Situation situation = smelting();
        situation.furnaceDone = true;
        situation.furnaceOnSurface = true;
        situation.underground = true;
        situation.surfaceTooLate = true;
        assertEquals(0.0, goals.score(GoalType.TEND_FURNACE, situation));

        // 굴 안에 놓은 화로는 밤에도 꺼낸다.
        situation.furnaceOnSurface = false;
        assertEquals(GoalType.TEND_FURNACE, select(situation));

        // 아침이 되면 지상의 화로도 꺼내러 간다.
        situation.furnaceOnSurface = true;
        situation.surfaceTooLate = false;
        assertEquals(GoalType.TEND_FURNACE, select(situation));
    }

    // 사용자가 본 문제: 화로를 쓰고 나서 그 자리에 두고 갔다.
    @Test
    void packsUpItsOwnFurnaceWhenNothingIsLeftToSmelt() {
        Situation situation = new Situation();
        situation.ownFurnaceNearby = true;
        situation.hasPickaxe = true;
        assertEquals(GoalType.PACK_UP_FURNACE, select(situation));

        // 곡괭이 없이 캐면 화로가 아이템으로 나오지 않는다.
        situation.hasPickaxe = false;
        assertEquals(0.0, goals.score(GoalType.PACK_UP_FURNACE, situation));
        situation.hasPickaxe = true;

        situation.inventoryFull = true;
        assertEquals(0.0, goals.score(GoalType.PACK_UP_FURNACE, situation));
    }

    @Test
    void keepsTheFurnaceWhileThereIsMoreToSmelt() {
        Situation situation = new Situation();
        situation.ownFurnaceNearby = true;
        situation.hasPickaxe = true;
        // 구울 고기가 남아 있다.
        situation.canCook = true;
        situation.rawFood = GoalSystem.COOK_MIN;
        assertEquals(GoalType.COOK_FOOD, select(situation));

        // 넣어 둔 것이 아직 화로에 있다.
        situation.rawFood = 0;
        situation.furnaceBusy = true;
        situation.furnaceDistance = 2.0;
        assertEquals(0.0, goals.score(GoalType.PACK_UP_FURNACE, situation));

        // 제련할 철이 모여 있다.
        situation.furnaceBusy = false;
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        situation.need = Situation.Need.IRON;
        situation.ironNeeded = 3;
        situation.rawIron = 3;
        situation.hasFuel = true;
        assertEquals(GoalType.SMELT_IRON, select(situation));
    }
}
