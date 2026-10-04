package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 상황에 맞는 단기 목표를 고른다.
 * 목표마다 점수 계산식이 따로 등록되어 있고, 점수가 가장 높은 목표가 선택된다. 점수 0 은 "지금은 해당 없음"이다.
 * 무엇을 향해 가는지(중기/장기 목표)는 Situation 의 nextMilestone 과 stage 로 들어오고, 여기서는 그것을 이루기 위해
 * 지금 할 일을 정한다.
 */
public final class GoalSystem {
    @FunctionalInterface
    public interface GoalEvaluator {
        double score(Situation situation);
    }

    public record Selection(GoalType goal, double score) {
    }

    /**
     * 후보 하나의 점수. 왜 이 목표를 골랐는지 설명할 때 다른 후보와 비교해 보여 준다.
     *
     * @param resting 계속 실패해서 쉬는 중이라 이번에는 고를 수 없는 목표인지
     */
    public record Ranked(GoalType goal, double score, boolean resting) {
    }

    // 이 점수 이상인 목표는 하던 행동을 중단시키고 즉시 시작한다. 그보다 낮은 목표는 현재 계획이 끝난 뒤에 시작한다.
    public static final double EMERGENCY_SCORE = 600.0;
    // 이 점수 이상인 목표(위험 탈출)는 계속 실패하더라도 쉬지 않고 다시 시도한다. 포기하면 죽기 때문이다.
    public static final double CRITICAL_SCORE = 900.0;
    // 한번 나무나 돌을 모으기 시작하면 여러 장비를 만들 만큼 모은 뒤에 제작으로 넘어간다.
    public static final int WOOD_TARGET = 16;
    public static final int STONE_TARGET = 16;
    // 횃불이 이보다 적으면 만들고, 석탄과 횃불이 아래 수량보다 적으면 석탄을 캔다.
    public static final int TORCH_MIN = 4;
    public static final int TORCH_STOCK = 16;
    public static final int COAL_MIN = 4;
    // 사냥감이 보일 때 이만큼 모일 때까지는 잡아서 음식을 비축한다.
    public static final int FOOD_STOCK = 8;
    // 땅속으로 내려가기 전에 음식을 이만큼은 챙긴다.
    public static final int TRIP_FOOD = 6;
    // 음식이 없을 때 허기가 이 값 이하로 떨어지면 하던 일을 멈추고 먹을 것을 찾으러 간다.
    public static final int FORAGE_BELOW = 10;
    public static final double HOME_LEASH = 24.0;
    // 거점으로 돌아올 때는 이 거리 안까지 온다.
    public static final double HOME_NEAR = 8.0;
    // 날고기가 이만큼 모이면 배가 고프지 않아도 미리 구워 둔다.
    public static final int COOK_MIN = 3;
    // 광맥을 이어서 캐는 것은 석탄이나 철이 이만큼 모일 때까지만 한다.
    public static final int VEIN_CAP = 64;
    // 빈칸이 이만큼 이하로 남으면 쓰지 않는 아이템을 버린다.
    public static final int CLEAN_BELOW_EMPTY = 2;
    // 빈칸이 이만큼 이하로 남으면 집 상자에 넣으러 간다. 버리는 것보다 먼저 한다.
    public static final int STORE_BELOW_EMPTY = 5;
    // 집 근처에 있을 때는 넣어 둘 것이 이만큼만 있어도 들러서 넣는다.
    public static final int STORE_NEARBY_SLOTS = 4;
    public static final double STORE_NEARBY_RANGE = 8.0;
    public static final double HOME_UPGRADE_RANGE = 48.0;
    // 숨은 자리에서 체력이 이 비율까지 돌아오면 다시 나간다.
    public static final double REFUGE_LEAVE_HEALTH = 0.8;
    // 화로에 넣어 둔 것이 있는 동안 이보다 멀어지면, 돌아가서 다 구워질 때까지 곁에서 기다린다.
    public static final double FURNACE_LEASH = 24.0;

    private final Map<GoalType, GoalEvaluator> evaluators = new EnumMap<>(GoalType.class);
    private final Map<GoalType, Long> cooldownUntil = new EnumMap<>(GoalType.class);

    public GoalSystem() {
        register(GoalType.IDLE, situation -> 1.0);
        register(GoalType.ESCAPE_DANGER, GoalSystem::escapeDanger);
        register(GoalType.SURVIVE, GoalSystem::survive);
        register(GoalType.FIGHT_HOSTILE, situation -> situation.combat == CombatSystem.Decision.FIGHT ? 800.0 : 0.0);
        register(GoalType.ASSIST_ALLY, GoalSystem::assistAlly);
        register(GoalType.FIND_FOOD, GoalSystem::findFood);
        register(GoalType.RETURN_HOME, GoalSystem::returnHome);
        register(GoalType.SLEEP, GoalSystem::sleep);
        register(GoalType.SHARE_FOOD, situation -> situation.allyWantsFood && situation.foodCount > 1 ? 480.0 : 0.0);
        register(GoalType.LOOT_CHEST, situation -> situation.knowsLootChest && !situation.inventoryFull ? 325.0 : 0.0);
        // 가방이 거의 차면 줍거나 캐는 일보다 먼저 정리한다. 그대로 두면 필요한 아이템을 얻으려고 캐도 줍지 못한다.
        register(GoalType.CLEAN_INVENTORY, GoalSystem::cleanInventory);
        register(GoalType.STORE_ITEMS, GoalSystem::storeItems);
        register(GoalType.PICKUP_ITEMS, situation -> situation.dropsNearby && !situation.inventoryFull ? 320.0 : 0.0);
        register(GoalType.FETCH_ITEMS, situation -> situation.chestHasNeeded && !situation.inventoryFull && !staysBelow(situation) ? 318.0 : 0.0);
        register(GoalType.PACK_UP_TABLE, GoalSystem::packUpTable);
        register(GoalType.SMELT_IRON, GoalSystem::smeltIron);
        register(GoalType.COOK_FOOD, GoalSystem::cookFood);
        register(GoalType.TEND_FURNACE, GoalSystem::tendFurnace);
        register(GoalType.PACK_UP_FURNACE, GoalSystem::packUpFurnace);
        register(GoalType.CRAFT_TORCH, situation -> situation.canCraftTorch && situation.torches < TORCH_MIN ? 295.0 : 0.0);
        register(GoalType.STOCK_FOOD, GoalSystem::stockFood);
        register(GoalType.MINE_COAL, GoalSystem::mineCoal);
        register(GoalType.CRAFT_WORKBENCH, situation -> canCraft(situation) && situation.nextMilestone == Milestone.CRAFTING_TABLE ? 300.0 : 0.0);
        register(GoalType.CRAFT_TOOL, situation -> canCraft(situation) && isCraftedMilestone(situation.nextMilestone) ? 300.0 : 0.0);
        // 일반 광맥을 더 캐기 전에 작업용 곡괭이를 보충한다. 다이아 채굴과 긴급 목표는 먼저 처리한다.
        register(GoalType.CRAFT_WORK_TOOL, situation -> situation.workPickaxeWanted ? 313.0 : 0.0);
        register(GoalType.BUILD_SHELTER, GoalSystem::buildShelter);
        register(GoalType.COLLECT_WOOD, GoalSystem::collectWood);
        register(GoalType.FIND_WOOD, situation -> wantsWood(situation) && !situation.knowsTree ? 240.0 : 0.0);
        register(GoalType.MINE_STONE, GoalSystem::mineStone);
        register(GoalType.MINE_IRON, GoalSystem::mineIron);
        // 다이아몬드는 귀하므로 보이면 다른 광석보다 먼저 캔다. 철보다 낮은 곡괭이로 캐면 아무것도 나오지 않는다.
        register(GoalType.MINE_DIAMOND, situation -> situation.knowsDiamond && situation.canMineDiamond && !situation.inventoryFull ? 314.0 : 0.0);
        register(GoalType.FIND_IRON, situation -> wantsIron(situation) && !situation.knowsIron ? 150.0 : 0.0);
        register(GoalType.FIND_DIAMOND, situation -> wantsDiamond(situation) && !situation.knowsDiamond ? 150.0 : 0.0);
        register(GoalType.EXPLORE, situation -> 50.0);
    }

    public void register(GoalType goal, GoalEvaluator evaluator) {
        evaluators.put(goal, evaluator);
    }

    public double score(GoalType goal, Situation situation) {
        GoalEvaluator evaluator = evaluators.get(goal);
        return evaluator == null ? 0.0 : evaluator.score(situation);
    }

    /**
     * 점수가 가장 높은 목표를 고른다. 최근에 계속 실패해서 쉬는 중인 목표는 건너뛰지만, 위험 탈출은 예외다.
     */
    public Selection select(Situation situation, long now) {
        GoalType bestGoal = GoalType.IDLE;
        double bestScore = 0.0;
        for (GoalType goal : GoalType.values()) {
            double score = score(goal, situation);
            if (score < CRITICAL_SCORE && isOnCooldown(goal, now)) continue;
            if (score > bestScore) {
                bestScore = score;
                bestGoal = goal;
            }
        }
        return new Selection(bestGoal, bestScore);
    }

    /**
     * 점수가 높은 순서로 후보를 돌려준다 (해당 없는 목표와 IDLE 은 뺀다). 판단 이유를 설명할 때 쓴다.
     */
    public List<Ranked> rank(Situation situation, long now, int limit) {
        List<Ranked> ranked = new ArrayList<>();
        for (GoalType goal : GoalType.values()) {
            if (goal == GoalType.IDLE) continue;
            double score = score(goal, situation);
            if (score <= 0.0) continue;
            ranked.add(new Ranked(goal, score, score < CRITICAL_SCORE && isOnCooldown(goal, now)));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return ranked.size() > limit ? List.copyOf(ranked.subList(0, limit)) : ranked;
    }

    public void cooldown(GoalType goal, long now, long ticks) {
        cooldownUntil.put(goal, now + ticks);
    }

    public boolean isOnCooldown(GoalType goal, long now) {
        Long until = cooldownUntil.get(goal);
        return until != null && now < until;
    }

    public void clearCooldown(GoalType goal) {
        cooldownUntil.remove(goal);
    }

    public void clearCooldowns() {
        cooldownUntil.clear();
    }

    /**
     * 땅속에 있는데 밤이거나 곧 밤이면, 지상에서 할 일(나무, 상자, 집)은 아침까지 미루고 올라가지 않는다.
     * 밤의 지상은 몬스터가 가장 많은 때이고, 그동안 땅속에서 할 수 있는 일이 있다.
     * 배고픔은 미룰 수 없으므로 먹을 것을 구하는 일은 여기에 들지 않는다.
     */
    public static boolean staysBelow(Situation situation) {
        return situation.underground && situation.surfaceTooLate;
    }

    /**
     * 다음에 이룰 것이 없어도 되는 항목(방패, 집)인데 지상에서만 할 수 있는 일로 막혀 있으면,
     * 땅속에서 밤을 나는 동안에는 건너뛰고 그다음 것을 준비한다.
     */
    public static boolean leavesForMorning(Situation situation) {
        Milestone milestone = situation.nextMilestone;
        if (milestone == null || !milestone.isOptional() || !staysBelow(situation)) return false;
        boolean readyToBuild = milestone == Milestone.SHELTER && situation.need == Situation.Need.NONE;
        return situation.need == Situation.Need.WOOD || readyToBuild;
    }

    private static double escapeDanger(Situation situation) {
        if (situation.inLava || situation.standingInDanger || situation.suffocating) return 1000.0;
        if (situation.drowning) return 950.0;
        if (situation.combat == CombatSystem.Decision.FLEE) return 900.0;
        return 0.0;
    }

    // 체력이 낮을 때 싸움을 피하고 먹거나 쉬어서 회복한다.
    private static double survive(Situation situation) {
        boolean canEat = situation.hasFood && situation.food < 20;
        // 숨은 자리에서는 체력이 넉넉히 돌아올 때까지 나가지 않는다. 회복할 방법이 없으면 기다려도 소용없으니 나간다.
        if (situation.sealedIn && situation.health < situation.maxHealth * REFUGE_LEAVE_HEALTH) {
            return canEat || situation.canRegenerate ? 850.0 : 0.0;
        }
        if (situation.healthState == SurvivalSystem.HealthState.OK) return 0.0;
        if (situation.combat == CombatSystem.Decision.FIGHT) return 0.0;
        boolean canRest = situation.canRegenerate && !situation.hostileNearby;
        return canEat || canRest ? 850.0 : 0.0;
    }

    // 동료가 도움을 청하면 하던 일을 멈추고 도우러 간다. 자기 몸이 성하지 않으면 나서지 않는다.
    private static double assistAlly(Situation situation) {
        if (!situation.allyNeedsHelp || situation.healthState != SurvivalSystem.HealthState.OK) return 0.0;
        return situation.combat == CombatSystem.Decision.FLEE ? 0.0 : 780.0;
    }

    private static double findFood(Situation situation) {
        boolean foodAvailable = situation.hasFood || situation.preyNearby;
        if (situation.starving) return foodAvailable ? 700.0 : 420.0;
        if (!situation.shouldEat) return 0.0;
        if (situation.hasFood) return 500.0;
        // 동료가 음식을 가져오는 중이면 굳이 사냥하러 떠나지 않는다.
        if (situation.foodOnTheWay) return 0.0;
        // 음식도 사냥감도 없으면 조금 고픈 정도로는 하던 일을 계속하고, 꽤 고파지면 구하러 나선다.
        if (situation.preyNearby) return 450.0;
        return situation.food <= FORAGE_BELOW ? 380.0 : 0.0;
    }

    /**
     * 사냥감이 눈앞에 있으면, 배가 고프지 않아도 잡아서 음식을 넉넉히 모아 둔다.
     * 땅속이나 먼 곳에서 배가 고파진 뒤에 구하려면 훨씬 오래 걸리기 때문이다. 첫 도구를 만들기 전에는 하지 않는다.
     */
    private static double stockFood(Situation situation) {
        if (!situation.hasPickaxe || situation.inventoryFull) return 0.0;
        if (situation.preyNearby) return situation.foodCount < FOOD_STOCK ? 260.0 : 0.0;
        // 광물을 찾아 땅속으로 내려갈 차례인데 음식이 모자라면, 내려가기 전에 지상에서 사냥감을 찾는다.
        // 땅속에는 사냥감이 없어서, 빈손으로 내려가면 굶주린 채로 올라와서 구해야 한다.
        boolean aboutToDescend = wantsIron(situation) && !situation.knowsIron || wantsDiamond(situation) && !situation.knowsDiamond;
        boolean shouldSearch = aboutToDescend && !situation.underground && !situation.foodSearchExhausted;
        return shouldSearch && situation.foodCount < TRIP_FOOD ? 160.0 : 0.0;
    }

    // 캔 철이 다음 장비를 만들 만큼 모였으면 화로에서 제련한다.
    private static double smeltIron(Situation situation) {
        // 화로에 넣어 둔 것이 있으면 그것을 꺼낸 다음에 넣는다.
        if (situation.furnaceBusy) return 0.0;
        if (situation.need != Situation.Need.IRON || situation.rawIron <= 0 || !situation.hasFuel) return 0.0;
        return situation.rawIron + situation.ironIngots >= situation.ironNeeded ? 300.0 : 0.0;
    }

    /**
     * 석탄은 횃불과 화로 연료에 쓴다. 눈앞의 광맥은 끝까지 캐고, 그 밖에는 모자랄 때만 찾아가서 캔다.
     * 광맥을 다 캐는 것은 돌 곡괭이부터 한다. 나무 곡괭이는 금방 부서져서(내구도 59), 광맥에 쓰면
     * 돌 곡괭이에 쓸 돌을 캐기 전에 부서진다.
     */
    private static double mineCoal(Situation situation) {
        if (!situation.hasPickaxe || situation.inventoryFull) return 0.0;
        if (situation.coalVeinNearby && situation.canMineIron && situation.coal < VEIN_CAP) return 312.0;
        if (!situation.knowsCoal) return 0.0;
        return situation.coal < COAL_MIN && situation.torches < TORCH_STOCK ? 220.0 : 0.0;
    }

    // 철은 다음 장비에 필요할 때 찾아가서 캐고, 눈앞에 보이는 광맥은 필요한 양과 상관없이 다 캔다(갑옷에 많이 든다).
    private static double mineIron(Situation situation) {
        if (situation.ironVeinNearby && situation.canMineIron && !situation.inventoryFull
                && situation.rawIron + situation.ironIngots < VEIN_CAP) return 312.0;
        return wantsIron(situation) && situation.knowsIron ? 200.0 : 0.0;
    }

    /**
     * 날고기를 굽는다. 배가 고픈데 익힌 음식이 없으면 날것을 먹기 전에 먼저 굽고(굶주린 상태면 날것이라도 먹는다),
     * 그렇지 않으면 날고기가 몇 개 모였을 때 미리 구워 둔다.
     */
    private static double cookFood(Situation situation) {
        if (situation.furnaceBusy) return 0.0;
        if (!situation.canCook || situation.rawFood <= 0) return 0.0;
        if (situation.shouldEat && situation.readyFood == 0 && !situation.starving) return 520.0;
        return situation.rawFood >= COOK_MIN ? 305.0 : 0.0;
    }

    /**
     * 화로에 넣어 둔 것을 챙긴다. 굽는 동안에는 화로 앞에 서 있지 않고 가까이에서 다른 일을 하다가, 다 구워지면 가서 꺼낸다.
     * 화로에서 너무 멀어지면 돌아가서 다 구워질 때까지 곁에서 기다린다. 넣어 둔 것을 두고 떠나지 않기 위해서다.
     */
    private static double tendFurnace(Situation situation) {
        if (!situation.furnaceBusy) return 0.0;
        // 땅속에서 밤을 나는 동안에는 지상에 있는 화로를 가지러 올라가지 않는다. 아침에 꺼낸다.
        if (staysBelow(situation) && situation.furnaceOnSurface) return 0.0;
        // 배가 고픈데 익힌 음식이 없으면, 굽고 있는 고기가 다 익기를 기다렸다가 꺼내 먹는다 (굶주린 상태면 날것이라도 먹는다).
        boolean waitingToEat = situation.furnaceCooksFood && situation.shouldEat && situation.readyFood == 0 && !situation.starving;
        if (waitingToEat) return 520.0;
        if (situation.furnaceDone) return 317.0;
        // 돌아오기 시작했으면 다 구워질 때까지 곁에 있는다. 경계 안에 들어서자마자 다시 떠나면 경계선에서 왔다 갔다 하기만 한다.
        boolean tending = situation.currentGoal == GoalType.TEND_FURNACE;
        return tending || situation.furnaceDistance > FURNACE_LEASH ? 299.0 : 0.0;
    }

    // 직접 놓은 화로는, 더 구울 것이 없으면 캐서 들고 다닌다. 화로는 곡괭이로 캐야 아이템으로 나온다.
    private static double packUpFurnace(Situation situation) {
        if (!situation.ownFurnaceNearby || situation.furnaceBusy || situation.inventoryFull || !situation.hasPickaxe) return 0.0;
        return smeltIron(situation) > 0.0 || cookFood(situation) > 0.0 ? 0.0 : 315.0;
    }

    // 직접 놓은 작업대는, 그 자리에서 더 만들 것이 없으면 캐서 들고 다닌다.
    private static double packUpTable(Situation situation) {
        if (!situation.ownTableNearby || situation.inventoryFull) return 0.0;
        return canCraft(situation) ? 0.0 : 315.0;
    }

    /**
     * 장비가 부족한 밤에는 거점으로 돌아가 머문다. 지어 둔 집이 있으면 집 안으로 들어가서 아침까지 나오지 않고,
     * 집이 없으면 거점에서 너무 멀어지지만 않는다.
     */
    private static double returnHome(Situation situation) {
        boolean shelter = situation.night && situation.nightPolicy == SurvivalSystem.NightPolicy.SHELTER;
        if (!shelter || !situation.homeKnown) return 0.0;
        if (situation.homeSheltered) return 600.0;
        // 돌아오기 시작했으면 거점 가까이 올 때까지 계속 온다. 경계 안에 들어서자마자 하던 일로 돌아가면
        // 곧 다시 경계를 넘게 되어 경계선에서 왔다 갔다 하기만 한다.
        boolean returning = situation.currentGoal == GoalType.RETURN_HOME;
        return situation.homeDistance > (returning ? HOME_NEAR : HOME_LEASH) ? 600.0 : 0.0;
    }

    // 잘 수 있으면 잔다. 밤을 건너뛰고, 죽었을 때 집에서 되살아나게 된다. 집에 숨어 있어야 하는 밤이면 기다리는 대신 잔다.
    private static double sleep(Situation situation) {
        if (!situation.canSleep || situation.hostileNearby) return 0.0;
        boolean hiding = situation.night && situation.nightPolicy == SurvivalSystem.NightPolicy.SHELTER;
        return hiding ? 610.0 : 350.0;
    }

    /**
     * 당장 쓰지 않는 것을 집 상자에 넣는다. 가방이 차 갈 때는 버리기 전에 넣으러 가고,
     * 마침 집 근처에 있을 때는 넣을 것이 조금만 있어도 들른다.
     */
    // 땅속에서는 가방이 차 가면 일찍 버린다. 지상에서는 상자에 넣을 기회가 있으므로 거의 다 찰 때까지 둔다.
    private static double cleanInventory(Situation situation) {
        int limit = situation.underground ? STORE_BELOW_EMPTY : CLEAN_BELOW_EMPTY;
        return situation.junkSlots > 0 && situation.emptySlots <= limit ? 345.0 : 0.0;
    }

    private static double storeItems(Situation situation) {
        if (!situation.chestAvailable || situation.storableSlots <= 0) return 0.0;
        if (staysBelow(situation)) return 0.0;
        // 깊은 굴 안에서 넘치는 조약돌 따위를 넣자고 집까지 다녀오지 않는다. 버릴 것이 있으면 그 자리에서 버린다.
        if (situation.underground && situation.junkSlots > 0) return 0.0;
        if (situation.emptySlots <= STORE_BELOW_EMPTY) return 346.0;
        boolean nearby = situation.homeDistance <= STORE_NEARBY_RANGE && situation.storableSlots >= STORE_NEARBY_SLOTS;
        return nearby ? 255.0 : 0.0;
    }

    /**
     * 집을 짓는다. 지을 블록이 다 모였을 때 시작하고, 한번 시작했으면 블록이 남아 있는 동안 이어서 짓는다.
     * 블록이 떨어지면 점수가 0 이 되어 돌이나 나무를 구하러 가고(need), 모이면 돌아와서 이어 짓는다.
     */
    private static double buildShelter(Situation situation) {
        if (staysBelow(situation)) return 0.0;
        if (situation.nextMilestone != Milestone.SHELTER) {
            // 다 지은 집에 빠진 시설(침대, 화로)을 들일 수 있게 됐으면, 집에서 멀지 않을 때 들러서 놓는다.
            return situation.homeUpgradeReady && situation.homeDistance <= HOME_UPGRADE_RANGE ? 256.0 : 0.0;
        }
        if (!situation.canBuildHere) return 0.0;
        if (!situation.shelterInProgress) return situation.need == Situation.Need.NONE ? 300.0 : 0.0;

        // 벽과 지붕을 다 올렸으면 문, 상자 같은 시설만 남는다. 그 재료가 있어야 이어서 할 수 있다.
        if (situation.buildBlocksNeeded <= 0) return situation.need == Situation.Need.NONE ? 311.0 : 0.0;
        // 짓는 중에는 가진 블록을 다 쓸 때까지 계속하고, 재료를 구하러 나갔을 때는 남은 양을 다 모은 뒤에 돌아온다.
        // 조금 모일 때마다 돌아오면 집과 채굴장 사이를 오가기만 하게 된다.
        boolean building = situation.currentGoal == GoalType.BUILD_SHELTER;
        boolean ready = building ? situation.buildBlocks > 0 : situation.buildBlocks >= situation.buildBlocksNeeded;
        return ready ? 311.0 : 0.0;
    }

    private static boolean canCraft(Situation situation) {
        return situation.nextMilestone != null && situation.need == Situation.Need.NONE;
    }

    // 제작으로 얻는 항목인지. 집 짓기처럼 월드에서 해야 하는 일은 제작 목표로 처리하지 않는다.
    private static boolean isCraftedMilestone(Milestone milestone) {
        return milestone != null && milestone != Milestone.CRAFTING_TABLE && milestone.kind() == Milestone.Kind.CRAFT;
    }

    private static boolean wantsWood(Situation situation) {
        if (situation.nextMilestone == null || staysBelow(situation)) return false;
        if (situation.need == Situation.Need.WOOD) return true;
        // 제련할 철은 모였는데 화로에 넣을 연료가 없으면 나무를 구한다.
        if (situation.need == Situation.Need.IRON && hasEnoughOre(situation) && !situation.hasFuel) return true;
        boolean gathering = situation.currentGoal == GoalType.COLLECT_WOOD || situation.currentGoal == GoalType.FIND_WOOD;
        return gathering && situation.plankEquivalent < WOOD_TARGET;
    }

    /**
     * 한번 베기 시작한 나무는 끝까지 벤다. 다만 도구가 하나도 없을 때는 만들 수 있는 도구부터 만들고(300) 돌아와서 마저 벤다.
     */
    private static double collectWood(Situation situation) {
        // 발판 위에 올라가 있으면 마저 베고 내려오는 것이 먼저다.
        if (situation.climbing) return 335.0;
        if (situation.treeUnfinished && !situation.inventoryFull) {
            boolean chopping = situation.currentGoal == GoalType.COLLECT_WOOD;
            double finish = chopping && situation.hasPickaxe ? 310.0 : 298.0;
            return Math.max(finish, wantsWood(situation) && situation.knowsTree && chopping ? 310.0 : 0.0);
        }
        if (!wantsWood(situation) || !situation.knowsTree) return 0.0;
        return situation.currentGoal == GoalType.COLLECT_WOOD ? 310.0 : 250.0;
    }

    private static double mineStone(Situation situation) {
        if (situation.nextMilestone == null || !situation.hasPickaxe) return 0.0;
        if (situation.need == Situation.Need.STONE) return situation.currentGoal == GoalType.MINE_STONE ? 310.0 : 250.0;
        boolean stocking = situation.currentGoal == GoalType.MINE_STONE && situation.nextMilestone.needsStone()
                && situation.cobblestone < STONE_TARGET;
        return stocking ? 310.0 : 0.0;
    }

    // 다음 장비에 필요한 만큼 철을 아직 못 모았을 때만 철을 찾으러 다닌다.
    private static boolean wantsIron(Situation situation) {
        return situation.need == Situation.Need.IRON && situation.hasPickaxe && !hasEnoughOre(situation);
    }

    private static boolean hasEnoughOre(Situation situation) {
        return situation.rawIron + situation.ironIngots >= situation.ironNeeded;
    }

    // 다이아몬드는 철 곡괭이 이상으로만 캘 수 있다. 필요한 만큼 모이면 더 찾아다니지 않는다.
    private static boolean wantsDiamond(Situation situation) {
        return situation.need == Situation.Need.DIAMOND && situation.canMineDiamond && situation.diamonds < situation.diamondsNeeded;
    }
}
