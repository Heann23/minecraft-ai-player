package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;

/**
 * 목표를 고르는 데 필요한 현재 상황의 요약.
 * 값만 담은 객체라서 Bukkit 없이 만들 수 있고, 목표 선택 로직을 서버 없이 테스트할 수 있다.
 */
public final class Situation {
    // 다음 장비를 만들기 위해 지금 부족한 재료의 종류
    public enum Need { NONE, WOOD, STONE, IRON, DIAMOND, FLINT, OTHER }

    public GoalType currentGoal = GoalType.IDLE;

    public double health = 20.0;
    public double maxHealth = 20.0;
    public int food = 20;
    public SurvivalSystem.HealthState healthState = SurvivalSystem.HealthState.OK;
    public boolean shouldEat;
    public boolean starving;
    public boolean canRegenerate = true;

    public boolean inLava;
    public boolean standingInDanger;
    public boolean suffocating;
    public boolean drowning;

    public CombatSystem.Decision combat = CombatSystem.Decision.NONE;
    public boolean hostileNearby;
    // 사방과 위가 막힌 자리에 숨어 있는지
    public boolean sealedIn;

    public boolean night;
    public SurvivalSystem.NightPolicy nightPolicy = SurvivalSystem.NightPolicy.CONTINUE;
    // 지금 땅속에서 올라가면 지상에서 밤을 맞게 되는지 (해 질 무렵부터 새벽까지)
    public boolean surfaceTooLate;
    // 같은 월드에 거점이 있는지, 그리고 거기까지의 수평 거리
    public boolean homeKnown;
    public double homeDistance;
    // 거점에 벽과 지붕이 다 지어진 집이 있는지, 지금 그 집 안에 있는지
    public boolean homeSheltered;
    public boolean insideHome;

    // 가까운 동료 AI 가 도움을 청했고 그 상대가 아직 살아 있는지
    public boolean allyNeedsHelp;
    // 배고픈 동료에게 음식을 가져다주기로 했는지
    public boolean allyWantsFood;
    // 동료가 음식을 가져오는 중이라 직접 구하러 갈 필요가 없는지
    public boolean foodOnTheWay;

    public boolean hasFood;
    // 가지고 있는 음식 아이템의 개수
    public int foodCount;
    public boolean preyNearby;
    // 깊은 땅속이나 구덩이 안이라 지상의 일(사냥, 나무)을 하려면 먼저 올라가야 하는지
    public boolean underground;
    // 사냥감을 한참 찾아다녔는데도 없어서, 당분간은 찾으러 다니지 않기로 했는지
    public boolean foodSearchExhausted;
    public boolean dropsNearby;
    public boolean inventoryFull;
    // 가방의 빈칸 수와, 버려도 되는 아이템이 든 칸 수
    public int emptySlots = 36;
    public int junkSlots;
    public boolean knowsLootChest;

    // 지금 속한 큰 단계 (장기 목표)
    public Stage stage = Stage.EARLY_SURVIVAL;
    // 다음에 이룰 것 (중기 목표). 전부 이뤘으면 null.
    public Milestone nextMilestone;
    public Need need = Need.NONE;
    public boolean hasPickaxe;
    // 철 이상의 곡괭이만 있고 막 쓸 돌 곡괭이가 없는데, 가진 재료로 하나 만들 수 있는지
    public boolean workPickaxeWanted;
    public int plankEquivalent;
    public int cobblestone;

    // 캐 놓은 철 원석, 제련한 철 주괴, 다음 장비에 필요한 주괴 수
    public int rawIron;
    public int ironIngots;
    public int ironNeeded;
    // 가진 다이아몬드와 다음 장비에 필요한 개수
    public int diamonds;
    public int diamondsNeeded;
    // 가진 자갈의 개수와, 캐러 갈 수 있는 자갈을 알고 있는지. 부싯돌은 자갈을 캘 때 가끔 나온다.
    public int gravel;
    public boolean knowsGravel;
    // 빈 양동이가 있는지와, 가서 뜰 수 있는 물을 알고 있는지
    public boolean emptyBucket;
    public boolean knowsWater;
    // 화로에 넣을 연료(석탄, 숯, 나무)가 있는지
    public boolean hasFuel;
    // 석탄과 숯의 개수, 횃불의 개수
    public int coal;
    public int torches;
    public boolean canCraftTorch;

    public boolean knowsTree;
    public boolean knowsIron;
    public boolean knowsCoal;
    // 바로 근처(몇 칸 안)에 보이는 광석이 있는지. 광맥을 캐기 시작했으면 끝까지 캔다.
    public boolean coalVeinNearby;
    public boolean ironVeinNearby;
    // 철 광석을 캘 수 있는 곡괭이(돌 이상)가 있는지. 금방 부서지는 나무 곡괭이뿐인지 가릴 때도 본다.
    public boolean canMineIron;
    // 캐러 갈 수 있는 다이아몬드 광석을 알고 있는지, 그리고 캘 수 있는 곡괭이(철 이상)가 있는지
    public boolean knowsDiamond;
    public boolean canMineDiamond;

    // 베다 만 나무가 남아 있는지 (위쪽 원목, 또는 내려와야 할 발판)
    public boolean treeUnfinished;
    // 나무를 베려고 쌓은 발판 위에 올라가 있는지. 이때는 아이템을 주우러 가지 않는다.
    public boolean climbing;

    // 날고기 개수와, 바로 먹어도 되는 음식(익힌 음식, 빵 등)의 개수
    public int rawFood;
    public int readyFood;
    // 화로를 쓸 수 있고 연료가 있어서 고기를 구울 수 있는지
    public boolean canCook;

    // 직접 놓은 작업대가 바로 근처에 있는지 (다 쓰면 챙겨 간다)
    public boolean ownTableNearby;

    // --- 화로 ---
    // 재료를 넣어 두고 온 화로가 있는지 (다 구워지면 가서 꺼낸다), 거기까지의 거리
    public boolean furnaceBusy;
    public double furnaceDistance;
    // 넣어 둔 것이 다 구워졌는지, 다 구워지기까지 남은 틱 수, 굽는 것이 음식인지
    public boolean furnaceDone;
    public int furnaceTicksLeft;
    public boolean furnaceCooksFood;
    // 그 화로가 땅속이 아니라 지상에 있는지. 땅속에서 밤을 나는 동안에는 지상의 화로를 가지러 올라가지 않는다.
    public boolean furnaceOnSurface;
    // 직접 놓은 빈 화로가 바로 근처에 있는지 (더 구울 것이 없으면 챙겨 간다)
    public boolean ownFurnaceNearby;

    // --- 집 짓기 ---
    // 짓다 만 집이 있는지
    public boolean shelterInProgress;
    // 집을 짓는 데 쓸 수 있는 블록(돌, 흙, 판자 환산)의 수와 아직 더 놓아야 하는 블록 수
    public int buildBlocks;
    public int buildBlocksNeeded;
    // 집을 지을 만큼 주변이 안전한지 (물속, 깊은 땅속, 다른 차원이 아님)
    public boolean canBuildHere;
    // 다 지은 집에 아직 없는 시설(침대, 화로)을 지금 가진 것으로 들일 수 있는지
    public boolean homeUpgradeReady;

    // --- 보관 ---
    // 거점에 쓸 수 있는 상자가 있는지
    public boolean chestAvailable;
    // 상자에 넣어 둘 만한 아이템이 든 칸 수
    public int storableSlots;
    // 다음 장비에 부족한 재료가 집 상자에 있는지
    public boolean chestHasNeeded;

    // --- 잠 ---
    // 거점에 침대가 있고, 지금 잘 수 있는 시간(밤이나 뇌우)인지
    public boolean canSleep;
}
