package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.CraftItemAction;
import me.herry.minecraftAI.ai.action.ExploreAreaAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.action.PlaceBlockAction;
import me.herry.minecraftAI.ai.action.SmeltItemAction;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.memory.MemoryEntry;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 작업대와 도구를 만드는 목표의 행동 계획.
 */
public final class CraftPlans {
    // 작업대에 손이 닿는 거리
    private static final double TABLE_REACH = 3.5;
    // 한 번에 제련하는 최대 개수와 한 번에 만드는 횃불 개수
    private static final int SMELT_BATCH = 16;
    private static final int TORCH_BATCH = 8;
    private static final int TORCHES_PER_COAL = 4;
    // 한 번에 굽는 고기 수. 배가 고플 때는 기다리는 시간을 줄이려고 조금만 굽는다.
    private static final int COOK_BATCH = 8;
    private static final int HUNGRY_COOK_BATCH = 2;
    // 이 거리 안에 있는 자기 작업대만 챙겨 간다. 멀리 두고 온 것은 거점으로 남겨 둔다.
    private static final double PACK_RANGE = 8.0;
    private static final double PACK_DROP_RADIUS = 6.0;
    // 블록을 놓을 자리가 없을 때 자리를 찾아 옮겨 가는 거리
    private static final double RELOCATE_MIN = 5.0;
    private static final double RELOCATE_MAX = 10.0;

    private CraftPlans() {
    }

    static List<Action> craftWorkbench(AIPlayer ai) {
        List<Action> actions = new ArrayList<>();
        if (!ai.getInventory().has(Material.CRAFTING_TABLE)) {
            if (!canCraft(ai, Material.CRAFTING_TABLE)) return List.of();
            actions.add(new CraftItemAction(Material.CRAFTING_TABLE, 1));
        }
        makeRoom(ai, actions, Material.CRAFTING_TABLE);
        actions.add(new PlaceBlockAction(Material.CRAFTING_TABLE, MemoryType.WORKBENCH));
        return actions;
    }

    static List<Action> craftTool(AIPlayer ai) {
        Milestone milestone = Progression.next(ai);
        if (milestone == null) return List.of();

        Material target = Progression.materialOf(milestone);
        CraftingSystem.CraftPlan plan = ai.getCrafting().plan(target, 1, ai.getInventory().snapshot());
        if (!plan.isFeasible()) return List.of();

        List<Action> actions = new ArrayList<>();
        if (plan.needsTable() && !ensureTable(ai, actions)) return List.of();
        actions.add(new CraftItemAction(target, 1));
        ai.getTeam().say(ai, Phrases.willCraft(milestone), true);
        return actions;
    }

    // 굴을 팔 때 막 쓸 돌 곡괭이를 만든다. 좋은 곡괭이는 그것이 있어야만 캘 수 있는 블록에 쓴다.
    static List<Action> craftWorkTool(AIPlayer ai) {
        CraftingSystem.CraftPlan plan = ai.getCrafting().plan(Material.STONE_PICKAXE, 1, ai.getInventory().snapshot());
        if (!plan.isFeasible()) return List.of();
        List<Action> actions = new ArrayList<>();
        if (plan.needsTable() && !ensureTable(ai, actions)) return List.of();
        actions.add(new CraftItemAction(Material.STONE_PICKAXE, 1));
        return actions;
    }

    /**
     * 캐 놓은 철 원석을 화로에서 제련한다. 가까운 화로, 가진 화로, 그 자리에서 만든 화로 순으로 쓰고,
     * 셋 다 안 되면(조약돌이나 작업대가 없을 때) 집에 있는 화로까지 돌아간다.
     */
    static List<Action> smeltIron(AIPlayer ai) {
        int raw = ai.getInventory().count(Material.RAW_IRON);
        if (raw <= 0) return List.of();

        List<Action> actions = new ArrayList<>();
        if (!prepareFurnace(ai, actions)) {
            Base home = ai.getWorldModel().homeIn(ai.getWorldId());
            if (home == null || home.furnace() == null) return List.of();
            ai.debug("No furnace here and nothing to make one with, going back to the one at home");
            // 계획을 다시 세울 때 화로가 가까워져 있으면 그때 제련한다.
            return List.of(new MoveToAction(PathGoal.reach(home.furnace(), TABLE_REACH), false));
        }
        actions.add(new SmeltItemAction(Material.RAW_IRON, Math.min(raw, SMELT_BATCH)));
        return actions;
    }

    // 횃불은 작업대 없이 만들 수 있다. 가진 석탄으로 만들 수 있는 만큼만 만든다.
    static List<Action> craftTorch(AIPlayer ai) {
        for (int amount = TORCH_BATCH; amount >= TORCHES_PER_COAL; amount -= TORCHES_PER_COAL) {
            if (canCraft(ai, Material.TORCH, amount)) return List.of(new CraftItemAction(Material.TORCH, amount));
        }
        return List.of();
    }

    /**
     * 날고기를 화로에 굽는다. 근처에 화로가 없으면 가진 화로를 놓고, 화로도 없으면 조약돌로 만들어서 놓는다.
     * 구울 방법이 없으면 빈 목록.
     */
    static List<Action> cookFood(AIPlayer ai) {
        Material raw = ai.getInventory().mostRawFood();
        if (raw == null || !ai.getInventory().hasFuel()) return List.of();

        List<Action> actions = new ArrayList<>();
        if (!prepareFurnace(ai, actions)) return List.of();
        // 배가 고플 때는 조금만 구워서 빨리 먹는다. 한 개에 10초가 걸린다.
        boolean hungry = ai.getPlayer().getFoodLevel() < ai.getConfig().eatBelow;
        int batch = Math.min(ai.getInventory().count(raw), hungry ? HUNGRY_COOK_BATCH : COOK_BATCH);
        actions.add(new SmeltItemAction(raw, batch));
        ai.getTeam().say(ai, Phrases.cooking(), false);
        return actions;
    }

    // 지금 가진 것으로 고기를 구울 수 있는지 (날고기, 연료, 그리고 화로를 쓸 방법)
    public static boolean canCook(AIPlayer ai) {
        if (ai.getInventory().mostRawFood() == null || !ai.getInventory().hasFuel()) return false;
        if (Progression.nearbyFurnace(ai) != null || ai.getInventory().has(Material.FURNACE)) return true;
        CraftingSystem.CraftPlan plan = ai.getCrafting().plan(Material.FURNACE, 1, ai.getInventory().snapshot());
        if (!plan.isFeasible()) return false;
        return !plan.needsTable() || Progression.nearbyTable(ai) != null || ai.getInventory().has(Material.CRAFTING_TABLE)
                || canCraft(ai, Material.CRAFTING_TABLE, 1);
    }

    // 화로 앞에 서도록 계획에 행동을 추가한다. 화로를 쓸 방법이 없으면 false.
    private static boolean prepareFurnace(AIPlayer ai, List<Action> actions) {
        BlockPoint furnace = Progression.nearbyFurnace(ai);
        if (furnace != null) {
            Player player = ai.getPlayer();
            boolean inReach = Positions.center(player.getWorld(), furnace).distance(player.getEyeLocation()) <= TABLE_REACH;
            if (!inReach) actions.add(new MoveToAction(PathGoal.reach(furnace, TABLE_REACH), true));
            return true;
        }
        if (!ai.getInventory().has(Material.FURNACE)) {
            CraftingSystem.CraftPlan plan = ai.getCrafting().plan(Material.FURNACE, 1, ai.getInventory().snapshot());
            if (!plan.isFeasible()) return false;
            if (plan.needsTable() && !ensureTable(ai, actions)) return false;
            actions.add(new CraftItemAction(Material.FURNACE, 1));
        }
        makeRoom(ai, actions, Material.FURNACE);
        actions.add(new PlaceBlockAction(Material.FURNACE, MemoryType.FURNACE));
        return true;
    }

    /**
     * 직접 놓았던 작업대를 캐서 다시 인벤토리에 넣는다. 다음에 필요하면 그 자리에서 다시 놓는다.
     */
    static List<Action> packUpTable(AIPlayer ai) {
        BlockPoint table = ownTableNearby(ai);
        if (table == null) return List.of();
        List<Action> actions = new ArrayList<>();
        Player player = ai.getPlayer();
        if (Positions.center(player.getWorld(), table).distance(player.getEyeLocation()) > TABLE_REACH) {
            actions.add(new MoveToAction(PathGoal.reach(table, TABLE_REACH), false));
        }
        actions.add(new BreakBlockAction(table));
        actions.add(new PickupItemAction(PACK_DROP_RADIUS, false));
        ai.debug("Packing up the crafting table at " + table);
        return actions;
    }

    // 이 AI 가 직접 놓은 작업대 중 가까이 있는 것. 없으면 null.
    public static @Nullable BlockPoint ownTableNearby(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BlockPoint feet = ai.getPosition();
        Base home = ai.getWorldModel().homeIn(world.getUID());
        for (MemoryEntry entry : ai.getMemory().all(MemoryType.OWN_WORKBENCH, world.getUID(), ai.getTicks())) {
            BlockPoint pos = entry.pos();
            if (pos.distance(feet) > PACK_RANGE || !Positions.isLoaded(world, pos)) continue;
            // 집 안에 들여놓은 작업대는 챙겨 가지 않는다.
            if (home != null && home.isInsideBuilding(world.getUID(), pos)) continue;
            if (Positions.block(world, pos).getType() == Material.CRAFTING_TABLE) return pos;
            ai.getMemory().forget(MemoryType.OWN_WORKBENCH, world.getUID(), pos);
        }
        return null;
    }

    // 작업대 앞에 서도록 계획에 행동을 추가한다. 작업대를 구할 방법이 없으면 false.
    private static boolean ensureTable(AIPlayer ai, List<Action> actions) {
        BlockPoint table = Progression.nearbyTable(ai);
        if (table != null) {
            Player player = ai.getPlayer();
            boolean inReach = Positions.center(player.getWorld(), table).distance(player.getEyeLocation()) <= TABLE_REACH;
            if (!inReach) actions.add(new MoveToAction(PathGoal.reach(table, TABLE_REACH), true));
            return true;
        }

        // 근처에 작업대가 없으면 가지고 있는 것을 설치하고, 그것도 없으면 새로 만든다.
        if (!ai.getInventory().has(Material.CRAFTING_TABLE)) {
            if (!canCraft(ai, Material.CRAFTING_TABLE)) return false;
            actions.add(new CraftItemAction(Material.CRAFTING_TABLE, 1));
        }
        makeRoom(ai, actions, Material.CRAFTING_TABLE);
        actions.add(new PlaceBlockAction(Material.CRAFTING_TABLE, MemoryType.WORKBENCH));
        return true;
    }

    /**
     * 파 내려간 좁은 굴 안에서는 블록을 놓을 빈칸이 없을 수 있다. 그럴 때는 옆 벽을 한 칸 파서 자리를 만든다.
     * 놓을 자리가 이미 있거나 팔 수 있는 벽이 없으면 아무것도 하지 않는다.
     */
    private static void makeRoom(AIPlayer ai, List<Action> actions, Material material) {
        if (PlaceBlockAction.hasSpot(ai, material)) return;
        BlockPoint nook = TerrainPlans.nookToDig(ai);
        if (nook != null) {
            ai.debug("No room to place " + material + " here, digging a nook at " + nook);
            actions.add(new BreakBlockAction(nook));
            return;
        }
        // 물속이나 허공 위의 좁은 턱처럼 놓을 자리도, 자리를 낼 벽도 없으면 자리를 옮긴 다음에 놓는다.
        // 그 자리에서 놓으려고만 하면 같은 실패를 되풀이하다가 쉬게 된다.
        List<Action> waterExit = TerrainPlans.leaveWater(ai);
        if (!waterExit.isEmpty()) {
            ai.debug("No room to place " + material + " in the water, getting out first");
            actions.addAll(waterExit);
            return;
        }
        ai.debug("No room to place " + material + " here and no wall to dig, moving a little first");
        boolean underground = TerrainPlans.isDeepUnderground(ai.getPlayer().getWorld(), ai.getPosition());
        actions.add(underground ? ExploreAreaAction.cave(RELOCATE_MIN, RELOCATE_MAX) : new ExploreAreaAction(RELOCATE_MIN, RELOCATE_MAX));
    }

    private static boolean canCraft(AIPlayer ai, Material material) {
        return canCraft(ai, material, 1);
    }

    private static boolean canCraft(AIPlayer ai, Material material, int amount) {
        return ai.getCrafting().plan(material, amount, ai.getInventory().snapshot()).isFeasible();
    }
}
