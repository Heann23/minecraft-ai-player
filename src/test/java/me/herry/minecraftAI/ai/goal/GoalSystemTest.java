package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalSystemTest {
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
    void looksForWoodWhenNoTreeIsKnown() {
        assertEquals(GoalType.FIND_WOOD, select(fresh()));
    }

    @Test
    void collectsWoodWhenTreeIsKnown() {
        Situation situation = fresh();
        situation.knowsTree = true;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    @Test
    void craftsWorkbenchOnceMaterialsAreReady() {
        Situation situation = fresh();
        situation.need = Situation.Need.NONE;
        situation.plankEquivalent = 4;
        assertEquals(GoalType.CRAFT_WORKBENCH, select(situation));
    }

    @Test
    void keepsCollectingWoodUntilTargetIsReached() {
        Situation situation = fresh();
        situation.need = Situation.Need.NONE;
        situation.knowsTree = true;
        situation.currentGoal = GoalType.COLLECT_WOOD;
        situation.plankEquivalent = 8;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));

        situation.plankEquivalent = GoalSystem.WOOD_TARGET;
        assertEquals(GoalType.CRAFT_WORKBENCH, select(situation));
    }

    @Test
    void craftsToolAfterWorkbench() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.WOODEN_PICKAXE;
        situation.need = Situation.Need.NONE;
        assertEquals(GoalType.CRAFT_TOOL, select(situation));
    }

    @Test
    void minesStoneOnlyWithPickaxe() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.STONE_PICKAXE;
        situation.need = Situation.Need.STONE;
        assertEquals(GoalType.EXPLORE, select(situation));

        situation.hasPickaxe = true;
        assertEquals(GoalType.MINE_STONE, select(situation));
    }

    @Test
    void minesIronUntilEnoughThenSmeltsAndCrafts() {
        Situation situation = new Situation();
        // 음식은 챙겨 둔 상태다 (없으면 내려가기 전에 사냥부터 한다).
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        situation.need = Situation.Need.IRON;
        // 내려갈 때 챙길 나무는 이미 있다 (없으면 나무부터 구한다).
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        situation.ironNeeded = 3;
        situation.hasPickaxe = true;
        assertEquals(GoalType.FIND_IRON, select(situation));

        situation.knowsIron = true;
        assertEquals(GoalType.MINE_IRON, select(situation));

        // 필요한 만큼 캤으면 더 캐지 않고 제련한다.
        situation.rawIron = 3;
        situation.hasFuel = true;
        assertEquals(GoalType.SMELT_IRON, select(situation));

        // 연료가 없으면 나무부터 구한다.
        situation.hasFuel = false;
        situation.knowsTree = true;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));

        situation.rawIron = 0;
        situation.ironIngots = 3;
        situation.need = Situation.Need.NONE;
        assertEquals(GoalType.CRAFT_TOOL, select(situation));
    }

    @Test
    void finishesVisibleCoalVeinBeforeLookingForIron() {
        Situation situation = new Situation();
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        situation.need = Situation.Need.IRON;
        // 내려갈 때 챙길 나무는 이미 있다 (없으면 나무부터 구한다).
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        situation.ironNeeded = 3;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.knowsCoal = true;
        // 석탄은 넉넉하지만 눈앞의 광맥이 남아 있다.
        situation.coal = 12;
        situation.coalVeinNearby = true;
        assertEquals(GoalType.MINE_COAL, select(situation));

        situation.coalVeinNearby = false;
        assertEquals(GoalType.FIND_IRON, select(situation));
    }

    // 회귀: 나무 곡괭이로 석탄 광맥을 다 캐다가 돌 곡괭이를 만들기 전에 곡괭이가 부서졌다.
    @Test
    void savesTheWoodenPickaxeForStoneInsteadOfMiningAVein() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.STONE_PICKAXE;
        situation.need = Situation.Need.STONE;
        situation.hasPickaxe = true;
        situation.coalVeinNearby = true;
        assertEquals(GoalType.MINE_STONE, select(situation));

        // 돌 곡괭이가 생긴 뒤에는 눈앞의 광맥을 다 캔다.
        situation.nextMilestone = Milestone.STONE_AXE;
        situation.canMineIron = true;
        assertEquals(GoalType.MINE_COAL, select(situation));
    }

    @Test
    void minesVisibleIronVeinOnlyWithStonePickaxe() {
        Situation situation = new Situation();
        situation.hasPickaxe = true;
        situation.ironVeinNearby = true;
        assertEquals(GoalType.EXPLORE, select(situation));

        situation.canMineIron = true;
        assertEquals(GoalType.MINE_IRON, select(situation));
    }

    @Test
    void makesToolsFirstThenComesBackToFinishTree() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.WOODEN_PICKAXE;
        situation.need = Situation.Need.NONE;
        situation.treeUnfinished = true;
        situation.currentGoal = GoalType.COLLECT_WOOD;
        // 도구가 없으면 만들 수 있는 도구부터 만든다.
        assertEquals(GoalType.CRAFT_TOOL, select(situation));

        // 곡괭이를 만든 뒤에는 돌을 캐러 가기 전에 베던 나무를 마저 벤다.
        situation.hasPickaxe = true;
        situation.nextMilestone = Milestone.STONE_PICKAXE;
        situation.need = Situation.Need.STONE;
        situation.currentGoal = GoalType.CRAFT_TOOL;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));

        situation.treeUnfinished = false;
        assertEquals(GoalType.MINE_STONE, select(situation));
    }

    @Test
    void climbingTreeFinishesBeforePickingUpDrops() {
        Situation situation = new Situation();
        situation.treeUnfinished = true;
        situation.climbing = true;
        situation.dropsNearby = true;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    @Test
    void cooksRawMeatInsteadOfEatingItRaw() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.shouldEat = true;
        situation.food = 12;
        situation.hasFood = true;
        situation.foodCount = 2;
        situation.rawFood = 2;
        situation.readyFood = 0;
        situation.canCook = true;
        assertEquals(GoalType.COOK_FOOD, select(situation));

        // 굶주렸으면 굽기를 기다리지 않고 날것이라도 먹는다.
        situation.starving = true;
        assertEquals(GoalType.FIND_FOOD, select(situation));

        // 화로를 쓸 수 없으면 날것을 먹는다.
        situation.starving = false;
        situation.canCook = false;
        assertEquals(GoalType.FIND_FOOD, select(situation));
    }

    @Test
    void cooksStockOfRawMeatWhenNotHungry() {
        Situation situation = new Situation();
        situation.canCook = true;
        situation.rawFood = 2;
        assertEquals(GoalType.EXPLORE, select(situation));

        situation.rawFood = GoalSystem.COOK_MIN;
        assertEquals(GoalType.COOK_FOOD, select(situation));
    }

    @Test
    void packsUpOwnTableOnlyWhenNothingMoreToCraft() {
        Situation situation = new Situation();
        situation.ownTableNearby = true;
        situation.nextMilestone = Milestone.STONE_AXE;
        situation.need = Situation.Need.NONE;
        assertEquals(GoalType.CRAFT_TOOL, select(situation));

        situation.need = Situation.Need.STONE;
        situation.hasPickaxe = true;
        assertEquals(GoalType.PACK_UP_TABLE, select(situation));
    }

    @Test
    void dropsJunkBeforeGatheringWhenBagIsAlmostFull() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.dropsNearby = true;
        situation.emptySlots = 1;
        situation.junkSlots = 5;
        assertEquals(GoalType.CLEAN_INVENTORY, select(situation));

        // 버릴 것이 없으면 정리하지 않는다.
        situation.junkSlots = 0;
        assertEquals(GoalType.PICKUP_ITEMS, select(situation));

        // 빈칸이 넉넉하면 쓰레기가 있어도 그대로 둔다.
        situation.junkSlots = 5;
        situation.emptySlots = 10;
        assertEquals(GoalType.PICKUP_ITEMS, select(situation));
    }

    @Test
    void minesKnownDiamondOnlyWithIronPickaxe() {
        Situation situation = new Situation();
        situation.hasPickaxe = true;
        situation.knowsDiamond = true;
        assertEquals(GoalType.EXPLORE, select(situation));

        situation.canMineDiamond = true;
        situation.coalVeinNearby = true;
        assertEquals(GoalType.MINE_DIAMOND, select(situation));
    }

    @Test
    void exploresWhenNothingIsLeftToDo() {
        assertEquals(GoalType.EXPLORE, select(new Situation()));
    }

    @Test
    void makesTorchesAndMinesCoalWhenShort() {
        Situation situation = new Situation();
        situation.hasPickaxe = true;
        situation.knowsCoal = true;
        assertEquals(GoalType.MINE_COAL, select(situation));

        situation.coal = 2;
        situation.canCraftTorch = true;
        assertEquals(GoalType.CRAFT_TORCH, select(situation));

        situation.canCraftTorch = false;
        situation.torches = GoalSystem.TORCH_STOCK;
        assertEquals(GoalType.EXPLORE, select(situation));
    }

    @Test
    void opensLootChestUnlessInventoryIsFull() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.knowsLootChest = true;
        assertEquals(GoalType.LOOT_CHEST, select(situation));

        situation.inventoryFull = true;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    // 회귀: 돌 곡괭이가 부서진 뒤 철 곡괭이로 굴을 파다가, 철 3개를 들인 그 곡괭이도 6분 만에 부서졌다.
    @Test
    void makesAStonePickaxeForDiggingWhenOnlyTheIronOneIsLeft() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_CHESTPLATE;
        situation.need = Situation.Need.IRON;
        // 내려갈 때 챙길 나무는 이미 있다 (없으면 나무부터 구한다).
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        situation.ironNeeded = 8;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.canMineDiamond = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.workPickaxeWanted = true;
        assertEquals(GoalType.CRAFT_WORK_TOOL, select(situation));

        situation.workPickaxeWanted = false;
        assertEquals(GoalType.FIND_IRON, select(situation));
    }

    @Test
    void replacesTheWorkPickaxeBeforeContinuingOrdinaryOreVeins() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_CHESTPLATE;
        situation.need = Situation.Need.IRON;
        // 내려갈 때 챙길 나무는 이미 있다 (없으면 나무부터 구한다).
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        situation.ironNeeded = 8;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.canMineDiamond = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.workPickaxeWanted = true;
        situation.coalVeinNearby = true;
        situation.ironVeinNearby = true;
        assertEquals(GoalType.CRAFT_WORK_TOOL, select(situation));
        // 철 곡괭이가 필요한 다이아는 바로 캐고, 위험 탈출도 보충보다 먼저 한다.
        situation.knowsDiamond = true;
        assertEquals(GoalType.MINE_DIAMOND, select(situation));
        situation.inLava = true;
        assertEquals(GoalType.ESCAPE_DANGER, select(situation));
    }

    @Test
    void findsMissingWorkPickaxeSticksBeforeSearchingOrFinishingOrdinaryOre() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_CHESTPLATE;
        situation.need = Situation.Need.IRON;
        situation.ironNeeded = 8;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.canMineDiamond = true;
        situation.workPickaxeNeedsWood = true;
        situation.coalVeinNearby = true;
        situation.ironVeinNearby = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        assertEquals(GoalType.FIND_WOOD, select(situation));
        situation.knowsTree = true;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    @Test
    void waitsBelowAtNightInsteadOfWearingOutTheBackupWithoutSticks() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_CHESTPLATE;
        situation.need = Situation.Need.IRON;
        situation.ironNeeded = 8;
        situation.hasPickaxe = true;
        situation.canMineIron = true;
        situation.workPickaxeNeedsWood = true;
        situation.coalVeinNearby = true;
        situation.ironVeinNearby = true;
        situation.underground = true;
        situation.surfaceTooLate = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        assertEquals(GoalType.EXPLORE, select(situation));
        situation.surfaceTooLate = false;
        assertEquals(GoalType.FIND_WOOD, select(situation));
    }

    @Test
    void missingWorkToolWoodDoesNotBlockRareOreOrEmergencyEscape() {
        Situation situation = new Situation();
        situation.workPickaxeNeedsWood = true;
        situation.hasPickaxe = true;
        situation.canMineDiamond = true;
        situation.knowsDiamond = true;
        assertEquals(GoalType.MINE_DIAMOND, select(situation));
        situation.inLava = true;
        assertEquals(GoalType.ESCAPE_DANGER, select(situation));
    }

    // 회귀: 깊은 굴에서 가방이 차자 넘치는 조약돌을 넣으러 집 상자까지 가려다, 길을 못 찾고 실패를 되풀이했다.
    @Test
    void dropsJunkUndergroundInsteadOfWalkingHomeToStoreIt() {
        Situation situation = new Situation();
        situation.chestAvailable = true;
        situation.storableSlots = 12;
        situation.emptySlots = GoalSystem.STORE_BELOW_EMPTY;
        situation.junkSlots = 4;
        situation.underground = true;
        assertEquals(GoalType.CLEAN_INVENTORY, select(situation));

        // 지상에서는 버리기 전에 상자에 넣는다.
        situation.underground = false;
        assertEquals(GoalType.STORE_ITEMS, select(situation));

        // 땅속이라도 버릴 것이 없으면 넣으러 간다.
        situation.underground = true;
        situation.junkSlots = 0;
        assertEquals(GoalType.STORE_ITEMS, select(situation));
    }

    @Test
    void returnsHomeAtNightOnlyWhenUnderEquippedAndFar() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.night = true;
        situation.nightPolicy = SurvivalSystem.NightPolicy.SHELTER;
        situation.homeKnown = true;
        situation.homeDistance = 40.0;
        assertEquals(GoalType.RETURN_HOME, select(situation));

        situation.homeDistance = 10.0;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));

        situation.homeDistance = 40.0;
        situation.nightPolicy = SurvivalSystem.NightPolicy.CONTINUE;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    // 회귀: 집이 없는 밤에 거점에서 멀어지면 돌아오다가, 경계 안에 들어서자마자 하던 일로 돌아가서 경계선에서 왔다 갔다 했다.
    @Test
    void keepsWalkingHomeOnceItStartedInsteadOfTurningBackAtTheLeash() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.night = true;
        situation.nightPolicy = SurvivalSystem.NightPolicy.SHELTER;
        situation.homeKnown = true;
        situation.homeDistance = GoalSystem.HOME_LEASH + 10.0;
        assertEquals(GoalType.RETURN_HOME, select(situation));

        // 돌아오는 중에는 경계 안에 들어와도 거점 가까이 갈 때까지 계속 온다.
        situation.currentGoal = GoalType.RETURN_HOME;
        situation.homeDistance = GoalSystem.HOME_LEASH - 4.0;
        assertEquals(GoalType.RETURN_HOME, select(situation));

        situation.homeDistance = GoalSystem.HOME_NEAR - 1.0;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    @Test
    void picksUpDropsUnlessInventoryIsFull() {
        Situation situation = fresh();
        situation.knowsTree = true;
        situation.dropsNearby = true;
        assertEquals(GoalType.PICKUP_ITEMS, select(situation));

        situation.inventoryFull = true;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));
    }

    @Test
    void cooldownSkipsGoalButNeverAnEmergency() {
        Situation situation = fresh();
        situation.knowsTree = true;
        goals.cooldown(GoalType.COLLECT_WOOD, 0L, 200L);
        assertEquals(GoalType.EXPLORE, goals.select(situation, 100L).goal());
        assertEquals(GoalType.COLLECT_WOOD, goals.select(situation, 200L).goal());

        situation.inLava = true;
        goals.cooldown(GoalType.ESCAPE_DANGER, 0L, 200L);
        assertEquals(GoalType.ESCAPE_DANGER, goals.select(situation, 100L).goal());
    }

    @Test
    void emergencyGoalsScoreAboveThreshold() {
        Situation situation = fresh();
        situation.inLava = true;
        assertTrue(goals.score(GoalType.ESCAPE_DANGER, situation) >= GoalSystem.EMERGENCY_SCORE);

        // 평소의 작업 목표는 하던 행동을 끊지 않아야 한다.
        situation.inLava = false;
        situation.knowsTree = true;
        assertTrue(goals.select(situation, 0L).score() < GoalSystem.EMERGENCY_SCORE);
    }
}
