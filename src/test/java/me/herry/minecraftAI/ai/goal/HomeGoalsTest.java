package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 집 짓기, 상자 보관, 잠, 다이아몬드처럼 단일 AI 진행에 새로 들어간 목표의 선택 규칙.
 */
class HomeGoalsTest {
    private final GoalSystem goals = new GoalSystem();

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 돌 도구와 화로까지 갖추고, 다음 중기 목표가 집 짓기인 상태.
    private static Situation readyToBuild() {
        Situation situation = new Situation();
        situation.stage = Stage.EARLY_SURVIVAL;
        situation.nextMilestone = Milestone.SHELTER;
        situation.hasPickaxe = true;
        situation.canBuildHere = true;
        situation.buildBlocksNeeded = 59;
        return situation;
    }

    @Test
    void startsBuildingOnlyWhenMaterialsAreReady() {
        Situation situation = readyToBuild();
        situation.need = Situation.Need.STONE;
        situation.buildBlocks = 20;
        // 블록이 모자라면 돌부터 구한다.
        assertEquals(GoalType.MINE_STONE, select(situation));

        situation.need = Situation.Need.WOOD;
        situation.knowsTree = true;
        assertEquals(GoalType.COLLECT_WOOD, select(situation));

        situation.need = Situation.Need.NONE;
        situation.buildBlocks = 60;
        assertEquals(GoalType.BUILD_SHELTER, select(situation));
    }

    @Test
    void doesNotBuildWhereItCannot() {
        Situation situation = readyToBuild();
        situation.need = Situation.Need.NONE;
        situation.buildBlocks = 60;
        situation.canBuildHere = false;
        // 물속이나 땅속 깊은 곳에서는 짓지 않고 다른 곳을 찾아본다.
        assertEquals(GoalType.EXPLORE, select(situation));
    }

    // 짓다가 블록이 떨어지면 구하러 가고, 남은 양을 다 모은 뒤에야 돌아온다.
    // 조금 모일 때마다 돌아오면 집과 채굴장 사이를 오가기만 하게 된다.
    @Test
    void gathersEverythingBeforeResumingAnInterruptedBuild() {
        Situation situation = readyToBuild();
        situation.shelterInProgress = true;
        situation.currentGoal = GoalType.BUILD_SHELTER;
        situation.buildBlocksNeeded = 30;
        situation.buildBlocks = 5;
        situation.need = Situation.Need.STONE;
        // 짓는 중에는 가진 블록을 다 쓸 때까지 계속 짓는다.
        assertEquals(GoalType.BUILD_SHELTER, select(situation));

        situation.buildBlocks = 0;
        assertEquals(GoalType.MINE_STONE, select(situation));

        situation.currentGoal = GoalType.MINE_STONE;
        situation.buildBlocks = 12;
        assertEquals(GoalType.MINE_STONE, select(situation));

        situation.buildBlocks = 30;
        situation.need = Situation.Need.NONE;
        assertEquals(GoalType.BUILD_SHELTER, select(situation));
    }

    @Test
    void finishesFixturesOnlyWithTheirMaterials() {
        Situation situation = readyToBuild();
        situation.shelterInProgress = true;
        situation.buildBlocksNeeded = 0;
        situation.need = Situation.Need.WOOD;
        situation.knowsTree = true;
        // 벽은 다 올렸는데 문을 만들 나무가 없으면 나무를 구하러 간다.
        assertEquals(GoalType.COLLECT_WOOD, select(situation));

        situation.need = Situation.Need.NONE;
        assertEquals(GoalType.BUILD_SHELTER, select(situation));
    }

    @Test
    void addsMissingFixturesToAFinishedHomeWhenNearby() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        situation.need = Situation.Need.IRON;
        // 내려갈 때 챙길 나무는 이미 있다 (없으면 나무부터 구한다).
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        situation.ironNeeded = 3;
        situation.homeKnown = true;
        situation.homeSheltered = true;
        situation.homeUpgradeReady = true;
        situation.homeDistance = 20.0;
        assertEquals(GoalType.BUILD_SHELTER, select(situation));

        // 멀리 있을 때는 그것 때문에 돌아가지 않는다.
        situation.homeDistance = 200.0;
        assertEquals(GoalType.EXPLORE, select(situation));
    }

    // 가방이 차 갈 때 집 상자가 있으면 버리기 전에 넣으러 간다.
    @Test
    void storesInsteadOfDroppingWhenAChestIsAvailable() {
        Situation situation = new Situation();
        situation.emptySlots = 2;
        situation.junkSlots = 3;
        assertEquals(GoalType.CLEAN_INVENTORY, select(situation));

        situation.chestAvailable = true;
        situation.storableSlots = 6;
        situation.homeDistance = 60.0;
        assertEquals(GoalType.STORE_ITEMS, select(situation));

        // 넣을 것이 없으면 쓰레기만 버린다.
        situation.storableSlots = 0;
        assertEquals(GoalType.CLEAN_INVENTORY, select(situation));
    }

    @Test
    void dropsByTheChestWhenPassingHome() {
        Situation situation = new Situation();
        situation.chestAvailable = true;
        situation.emptySlots = 20;
        situation.storableSlots = GoalSystem.STORE_NEARBY_SLOTS;
        situation.homeDistance = 5.0;
        assertEquals(GoalType.STORE_ITEMS, select(situation));

        // 가방에 여유가 있는데 멀리 있으면 굳이 돌아가지 않는다.
        situation.homeDistance = 50.0;
        assertEquals(GoalType.EXPLORE, select(situation));
    }

    // 필요한 재료가 집 상자에 있으면 새로 캐러 가지 않고 꺼내 온다.
    @Test
    void fetchesFromTheChestBeforeMiningAgain() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        situation.need = Situation.Need.IRON;
        // 내려갈 때 챙길 나무는 이미 있다 (없으면 나무부터 구한다).
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        situation.ironNeeded = 3;
        situation.hasPickaxe = true;
        situation.knowsIron = true;
        assertEquals(GoalType.MINE_IRON, select(situation));

        situation.chestHasNeeded = true;
        assertEquals(GoalType.FETCH_ITEMS, select(situation));

        situation.inventoryFull = true;
        assertEquals(GoalType.MINE_IRON, select(situation));
    }

    @Test
    void sleepsAtNightWhenItCan() {
        Situation situation = new Situation();
        situation.night = true;
        situation.canSleep = true;
        assertEquals(GoalType.SLEEP, select(situation));

        // 몬스터가 가까이 있으면 잘 수 없다.
        situation.hostileNearby = true;
        assertEquals(GoalType.EXPLORE, select(situation));
    }

    // 장비가 약한 밤에는 지어 둔 집 안으로 들어가서 아침까지 나오지 않고, 침대가 있으면 기다리는 대신 잔다.
    @Test
    void hidesInTheShelterAtNightWhenWeak() {
        Situation situation = new Situation();
        situation.night = true;
        situation.nightPolicy = SurvivalSystem.NightPolicy.SHELTER;
        situation.homeKnown = true;
        situation.homeSheltered = true;
        situation.homeDistance = 5.0;
        assertEquals(GoalType.RETURN_HOME, select(situation));

        situation.insideHome = true;
        situation.homeDistance = 0.0;
        assertEquals(GoalType.RETURN_HOME, select(situation));

        situation.canSleep = true;
        assertEquals(GoalType.SLEEP, select(situation));

        // 장비가 충분하면 밤에도 하던 일을 한다.
        situation.canSleep = false;
        situation.nightPolicy = SurvivalSystem.NightPolicy.CONTINUE;
        assertEquals(GoalType.EXPLORE, select(situation));
    }

    @Test
    void searchesForDiamondsOnlyWithAnIronPickaxe() {
        Situation situation = new Situation();
        // 음식은 챙겨 둔 상태다 (없으면 내려가기 전에 사냥부터 한다).
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.stage = Stage.DIAMOND_AGE;
        situation.nextMilestone = Milestone.DIAMOND_PICKAXE;
        situation.need = Situation.Need.DIAMOND;
        // 내려갈 때 챙길 나무는 이미 있다 (없으면 나무부터 구한다).
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        situation.diamondsNeeded = 3;
        situation.hasPickaxe = true;
        assertEquals(GoalType.EXPLORE, select(situation));

        situation.canMineDiamond = true;
        assertEquals(GoalType.FIND_DIAMOND, select(situation));

        situation.knowsDiamond = true;
        assertEquals(GoalType.MINE_DIAMOND, select(situation));

        // 필요한 만큼 모이면 더 찾아다니지 않고 만든다.
        situation.knowsDiamond = false;
        situation.diamonds = 3;
        situation.need = Situation.Need.NONE;
        assertEquals(GoalType.CRAFT_TOOL, select(situation));
    }

    // 집 짓기는 제작 목표(CRAFT_TOOL)로 처리하지 않는다.
    @Test
    void shelterIsNotTreatedAsACraftedItem() {
        Situation situation = readyToBuild();
        situation.need = Situation.Need.NONE;
        situation.buildBlocks = 60;
        situation.canBuildHere = false;
        assertEquals(0.0, goals.score(GoalType.CRAFT_TOOL, situation));
    }

    // 왜 이 목표를 골랐는지 설명할 때 쓰는 후보 순위
    @Test
    void ranksCandidatesByScore() {
        Situation situation = new Situation();
        situation.night = true;
        situation.canSleep = true;
        situation.dropsNearby = true;

        List<GoalSystem.Ranked> ranked = goals.rank(situation, 0L, 3);
        assertEquals(3, ranked.size());
        assertEquals(GoalType.SLEEP, ranked.get(0).goal());
        assertEquals(GoalType.PICKUP_ITEMS, ranked.get(1).goal());
        assertEquals(GoalType.EXPLORE, ranked.get(2).goal());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());

        // 쉬는 중인 목표는 순위에는 나오지만 표시가 붙고, 실제 선택에서는 빠진다.
        goals.cooldown(GoalType.SLEEP, 0L, 200L);
        assertTrue(goals.rank(situation, 10L, 3).get(0).resting());
        assertFalse(goals.rank(situation, 300L, 3).get(0).resting());
        assertEquals(GoalType.PICKUP_ITEMS, goals.select(situation, 10L).goal());
    }
}
