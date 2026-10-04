package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.DepositItemsAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.SleepAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.action.WithdrawItemsAction;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.inventory.StorageItems;
import me.herry.minecraftAI.ai.inventory.StoragePolicy;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.world.Base;
import me.herry.minecraftAI.ai.world.WorldModel;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 집에서 하는 일의 계획: 귀환, 상자에 넣기와 꺼내기, 잠자기. 그리고 그 일들을 할 수 있는 상태인지의 판단.
 */
public final class HomePlans {
    // 이보다 멀리 있으면 상자에 넣으러 돌아가지 않는다. 오가는 데 드는 시간이 너무 길다.
    private static final double STORAGE_TRIP_RANGE = 96.0;
    private static final double SLEEP_TRIP_RANGE = 48.0;
    private static final double CHEST_REACH = 3.5;
    private static final double HOME_RADIUS = 3.0;
    private static final double INSIDE_RADIUS = 1.0;
    private static final int HOME_WAIT_TICKS = 100;
    // 음식이 이보다 적은데 상자에 음식이 있으면 꺼내 온다.
    private static final int FOOD_LOW = 4;
    private static final int FOOD_FETCH = 12;
    private static final int WOOD_FETCH = 16;
    private static final int STONE_FETCH = 32;
    // 집의 중심에서 이 거리(가로세로 중 큰 쪽) 안에서는 땅을 파지 않는다. 집은 중심에서 2칸까지다.
    private static final int DIG_CLEARANCE = 4;
    private static final int LEAVE_DISTANCE = 8;

    private HomePlans() {
    }

    /**
     * 거점과 관련된 상황(집까지의 거리, 상자에 넣을 것, 상자에 있는 재료, 잘 수 있는지)을 채운다.
     */
    public static void assess(AIPlayer ai, Situation situation) {
        World world = ai.getPlayer().getWorld();
        WorldModel model = ai.getWorldModel();
        Base home = model.homeIn(world.getUID());
        situation.homeKnown = home != null;
        if (home == null) return;

        BlockPoint feet = ai.getPosition();
        BlockPoint center = home.center();
        situation.homeDistance = Math.hypot(feet.x() - center.x(), feet.z() - center.z());
        situation.homeSheltered = home.isSheltered();
        situation.insideHome = home.isInsideBuilding(world.getUID(), feet);
        situation.homeUpgradeReady = BuildPlans.upgradeReady(ai, home);

        boolean storageInRange = !home.chests().isEmpty() && situation.homeDistance <= STORAGE_TRIP_RANGE;
        if (storageInRange) {
            situation.chestAvailable = !model.isStorageFull();
            situation.storableSlots = StoragePolicy.deposits(StorageItems.stacksOf(ai.getPlayer().getInventory()), contextOf(situation)).size();
            situation.chestHasNeeded = wantedFromStorage(model, situation) != null;
        }

        boolean bedtime = situation.night || ai.getPerception().isThundering();
        situation.canSleep = home.bed() != null && bedtime && world.getEnvironment() == World.Environment.NORMAL
                && situation.homeDistance <= SLEEP_TRIP_RANGE && ai.getTicks() >= ai.getSleepRetryAfter();
    }

    // 지금 만들려는 것에 필요한 재료는 상자에 넣지 않는다.
    public static StoragePolicy.Context contextOf(Situation situation) {
        Milestone next = situation.nextMilestone;
        return new StoragePolicy.Context(
                situation.need == Situation.Need.WOOD,
                situation.need == Situation.Need.STONE || next != null && next.needsStone(),
                situation.need == Situation.Need.IRON || next != null && next.needsIron(),
                situation.need == Situation.Need.DIAMOND || next != null && next.diamondCost() > 0);
    }

    /**
     * 지금 부족한 것 중 기억 속의 상자에 들어 있는 것. 없으면 null.
     */
    private static @Nullable Wanted wantedFromStorage(WorldModel model, Situation situation) {
        Wanted wanted = switch (situation.need) {
            case IRON -> new Wanted(type -> type == Material.IRON_INGOT || type == Material.RAW_IRON,
                    Math.max(1, situation.ironNeeded - situation.rawIron - situation.ironIngots));
            case DIAMOND -> new Wanted(type -> type == Material.DIAMOND, Math.max(1, situation.diamondsNeeded - situation.diamonds));
            case WOOD -> new Wanted(type -> Tag.LOGS.isTagged(type) || Tag.PLANKS.isTagged(type), WOOD_FETCH);
            case STONE -> new Wanted(Tag.ITEMS_STONE_TOOL_MATERIALS::isTagged, STONE_FETCH);
            default -> null;
        };
        if (wanted != null && isStored(model, wanted.filter())) return wanted;
        // 음식이 떨어져 가는데 상자에 넣어 둔 음식이 있으면 꺼내 온다.
        if (situation.foodCount < FOOD_LOW) {
            Wanted food = new Wanted(InventorySystem::isSafeFood, FOOD_FETCH);
            if (isStored(model, food.filter())) return food;
        }
        return null;
    }

    private record Wanted(Predicate<Material> filter, int amount) {
    }

    private static boolean isStored(WorldModel model, Predicate<Material> filter) {
        return chestHolding(model, filter) != null;
    }

    private static @Nullable WorldModel.Place chestHolding(WorldModel model, Predicate<Material> filter) {
        for (Map.Entry<WorldModel.Place, Map<String, Integer>> entry : model.getStorage().entrySet()) {
            for (Map.Entry<String, Integer> item : entry.getValue().entrySet()) {
                Material type = Material.getMaterial(item.getKey());
                if (type != null && item.getValue() > 0 && filter.test(type)) return entry.getKey();
            }
        }
        return null;
    }

    static List<Action> returnHome(AIPlayer ai) {
        Base home = ai.getWorldModel().homeIn(ai.getWorldId());
        if (home == null) return List.of();
        List<Action> climb = climbFirst(ai);
        if (!climb.isEmpty()) return climb;
        // 집이 지어져 있으면 안으로 들어가서 기다리고, 아직 없으면 거점 근처에 머문다.
        double radius = home.isSheltered() ? INSIDE_RADIUS : HOME_RADIUS;
        return List.of(new MoveToAction(PathGoal.arrive(home.center(), radius), false), new WaitAction(HOME_WAIT_TICKS));
    }

    static List<Action> storeItems(AIPlayer ai) {
        Base home = ai.getWorldModel().homeIn(ai.getWorldId());
        Situation situation = ai.getLastSituation();
        if (home == null || home.chests().isEmpty() || situation == null) return List.of();
        List<Action> climb = climbFirst(ai);
        if (!climb.isEmpty()) return climb;
        BlockPoint chest = home.chests().getFirst();
        return List.of(approach(ai, home, chest), new DepositItemsAction(chest, contextOf(situation)));
    }

    static List<Action> fetchItems(AIPlayer ai) {
        Base home = ai.getWorldModel().homeIn(ai.getWorldId());
        Situation situation = ai.getLastSituation();
        if (home == null || situation == null) return List.of();
        Wanted wanted = wantedFromStorage(ai.getWorldModel(), situation);
        if (wanted == null) return List.of();
        WorldModel.Place place = chestHolding(ai.getWorldModel(), wanted.filter());
        if (place == null || !place.world().equals(ai.getWorldId())) return List.of();
        List<Action> climb = climbFirst(ai);
        if (!climb.isEmpty()) return climb;
        ai.debug("Fetching needed items from the chest at " + place.pos());
        return List.of(approach(ai, home, place.pos()), new WithdrawItemsAction(place.pos(), wanted.filter(), wanted.amount()));
    }

    static List<Action> sleep(AIPlayer ai) {
        Base home = ai.getWorldModel().homeIn(ai.getWorldId());
        if (home == null || home.bed() == null) return List.of();
        List<Action> climb = climbFirst(ai);
        if (!climb.isEmpty()) return climb;
        return List.of(new MoveToAction(PathGoal.arrive(home.center(), INSIDE_RADIUS), false), new SleepAction(home.bed()));
    }

    /**
     * 깊은 땅속에서 집으로 가려면 먼저 지상으로 올라간다. 굴 바닥에서 집까지는 한 번에 길을 찾지 못해서,
     * 파 놓은 굴을 따라 올라가거나 계단을 내는 계획을 먼저 실행하고 지상에서 다시 계획을 세운다.
     *
     * @return 올라가야 하면 그 계획, 이미 지상이면 빈 목록
     */
    private static List<Action> climbFirst(AIPlayer ai) {
        return TerrainPlans.needsToClimb(ai) ? TerrainPlans.climbOut(ai) : List.of();
    }

    /**
     * 집 안이나 집 바로 옆에 서 있으면, 땅을 파기 전에 먼저 문밖으로 몇 칸 나간다.
     * 서 있는 자리에서 바로 파 내려가면 집의 바닥과 벽 밑을 허물게 된다.
     *
     * @return 나가야 하면 그 이동, 이미 집에서 떨어져 있으면 빈 목록
     */
    static List<Action> leaveBuilding(AIPlayer ai) {
        Base home = ai.getWorldModel().homeIn(ai.getWorldId());
        if (home == null || !home.hasBuilding()) return List.of();
        BlockPoint feet = ai.getPosition();
        BlockPoint center = home.center();
        int dx = feet.x() - center.x();
        int dz = feet.z() - center.z();
        if (Math.max(Math.abs(dx), Math.abs(dz)) > DIG_CLEARANCE) return List.of();

        // 문이 있으면 문 쪽으로, 없으면 지금 서 있는 쪽으로 나간다.
        BlockPoint entrance = home.entrance();
        int outX = entrance != null ? Integer.signum(entrance.x() - center.x()) : Integer.signum(dx);
        int outZ = entrance != null ? Integer.signum(entrance.z() - center.z()) : Integer.signum(dz);
        if (outX == 0 && outZ == 0) outZ = 1;
        BlockPoint outside = center.offset(outX * LEAVE_DISTANCE, 0, outZ * LEAVE_DISTANCE);
        ai.debug("Stepping away from home before digging, toward " + outside);
        return List.of(new MoveToAction(PathGoal.arrive(outside, 2.0), false));
    }

    /**
     * 상자 앞으로 간다. 집 안의 상자는 벽 너머에서 손을 뻗지 않고 집 안으로 들어가서 연다.
     */
    private static Action approach(AIPlayer ai, Base home, BlockPoint chest) {
        if (home.isInsideBuilding(ai.getWorldId(), chest)) return new MoveToAction(PathGoal.arrive(home.center(), INSIDE_RADIUS), false);
        return new MoveToAction(PathGoal.reach(chest, CHEST_REACH), false);
    }
}
