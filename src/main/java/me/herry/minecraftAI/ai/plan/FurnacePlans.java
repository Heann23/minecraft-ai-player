package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.CollectFurnaceAction;
import me.herry.minecraftAI.ai.action.CraftItemAction;
import me.herry.minecraftAI.ai.action.Furnaces;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.crafting.FuelMath;
import me.herry.minecraftAI.ai.crafting.FurnaceJob;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.memory.MemoryEntry;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.inventory.FurnaceInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 화로에 넣어 둔 것을 챙기고, 쓰고 난 화로를 다시 가져가는 행동 계획.
 * 굽는 동안에는 화로 앞에 서 있지 않고 다른 일을 하므로, 어느 화로에 무엇을 넣어 두었는지를 AIPlayer 가 기억한다 (FurnaceJob).
 */
public final class FurnacePlans {
    // 이보다 먼 화로에 넣어 둔 것은 지금은 없는 것으로 친다 (죽어서 먼 곳에서 되살아났을 때 등). 가까이 오면 다시 챙긴다.
    private static final double JOB_RANGE = 96.0;
    // 굽는 동안 화로 곁에서 한 번에 기다리는 시간
    private static final int WAIT_TICKS = 40;

    private FurnacePlans() {
    }

    // 목표를 고를 때 보는 화로 관련 사실을 채운다.
    public static void assess(AIPlayer ai, Situation situation) {
        FurnaceJob job = activeJob(ai);
        if (job != null) {
            World world = ai.getPlayer().getWorld();
            situation.furnaceBusy = true;
            situation.furnaceCooksFood = job.food();
            situation.furnaceDistance = job.pos().distance(ai.getPosition());
            situation.furnaceDone = isDone(ai, job);
            situation.furnaceOnSurface = !Positions.isLoaded(world, job.pos()) || !TerrainPlans.isDeepUnderground(world, job.pos());
        }
        situation.ownFurnaceNearby = ownFurnaceNearby(ai) != null;
    }

    /**
     * 지금 챙길 수 있는, 넣어 둔 것이 있는 화로. 없으면 null.
     * 화로가 없어졌으면 기록을 지우고, 너무 멀거나 갈 길이 없었던 화로는 기록만 남겨 두고 지금은 없는 것으로 친다.
     */
    static @Nullable FurnaceJob activeJob(AIPlayer ai) {
        FurnaceJob job = ai.getFurnaceJob();
        if (job == null || !job.world().equals(ai.getWorldId())) return null;
        if (job.pos().distance(ai.getPosition()) > JOB_RANGE) return null;

        World world = ai.getPlayer().getWorld();
        if (Positions.isLoaded(world, job.pos()) && Positions.block(world, job.pos()).getType() != Material.FURNACE) {
            ai.debug("The furnace at " + job.pos() + " is gone, forgetting what was in it");
            ai.setFurnaceJob(null);
            return null;
        }
        return ai.getMemory().contains(MemoryType.UNREACHABLE, ai.getWorldId(), job.pos(), ai.getTicks()) ? null : job;
    }

    // 넣어 둔 것이 다 구워졌는지. 화로가 멈춰서 더 기다려도 소용없을 때도 꺼내러 간다.
    private static boolean isDone(AIPlayer ai, FurnaceJob job) {
        long now = ai.getTicks();
        FurnaceInventory furnace = Furnaces.inventory(ai.getPlayer().getWorld(), job.pos());
        // 청크가 내려가 있어서 볼 수 없으면 예상 시각으로 판단한다.
        if (furnace == null) return now >= job.readyAt();
        return Furnaces.isSmelted(furnace) || job.isOverdue(now);
    }

    /**
     * 넣어 둔 화로로 가서, 다 구워졌으면 꺼내고 아직이면 곁에서 기다린다.
     */
    static List<Action> tendFurnace(AIPlayer ai) {
        FurnaceJob job = activeJob(ai);
        if (job == null) return List.of();
        List<Action> actions = new ArrayList<>();
        CraftPlans.approach(ai, actions, job.pos(), true);
        actions.add(isDone(ai, job) ? new CollectFurnaceAction(job.pos()) : new WaitAction(WAIT_TICKS));
        return actions;
    }

    /**
     * 직접 놓았던 화로를 캐서 다시 인벤토리에 넣는다. 다음에 필요하면 그 자리에서 다시 놓는다.
     */
    static List<Action> packUpFurnace(AIPlayer ai) {
        BlockPoint furnace = ownFurnaceNearby(ai);
        if (furnace == null) return List.of();
        List<Action> actions = new ArrayList<>();
        CraftPlans.approach(ai, actions, furnace, false);
        actions.add(new BreakBlockAction(furnace));
        actions.add(new PickupItemAction(CraftPlans.PACK_DROP_RADIUS, false));
        ai.debug("Packing up the furnace at " + furnace);
        return actions;
    }

    // 이 AI 가 직접 놓은 화로 중 가까이 있고 비어 있는 것. 없으면 null.
    public static @Nullable BlockPoint ownFurnaceNearby(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BlockPoint feet = ai.getPosition();
        Base home = ai.getWorldModel().homeIn(world.getUID());
        for (MemoryEntry entry : ai.getMemory().all(MemoryType.OWN_FURNACE, world.getUID(), ai.getTicks())) {
            BlockPoint pos = entry.pos();
            if (pos.distance(feet) > CraftPlans.PACK_RANGE || !Positions.isLoaded(world, pos)) continue;
            // 집 안에 들여놓은 화로는 챙겨 가지 않는다.
            if (home != null && home.isInsideBuilding(world.getUID(), pos)) continue;
            FurnaceInventory furnace = Furnaces.inventory(world, pos);
            if (furnace == null) {
                ai.getMemory().forget(MemoryType.OWN_FURNACE, world.getUID(), pos);
                continue;
            }
            // 안에 든 것이 있으면 캐지 않는다. 캐면 굽던 것이 바닥에 쏟아진다.
            if (Furnaces.isEmpty(furnace)) return pos;
        }
        return null;
    }

    /**
     * 연료가 원목뿐이면 굽기 전에 필요한 만큼 판자로 만든다. 원목 하나는 판자 하나와 똑같이 1.5개를 굽는데,
     * 판자로 바꾸면 4개가 되어 6개를 굽는다. 판자는 작업대 없이 만들 수 있다.
     * 석탄이 있거나 가진 판자로 충분하면 아무것도 하지 않는다.
     */
    static void splitLogsForFuel(AIPlayer ai, List<Action> actions, int items) {
        Map<Material, Integer> stock = ai.getInventory().snapshot();
        if (stock.getOrDefault(Material.COAL, 0) + stock.getOrDefault(Material.CHARCOAL, 0) > 0) return;

        int needed = FuelMath.fuelFor(items, FuelMath.ITEMS_PER_WOOD);
        Material bestPlanks = null;
        int bestAmount = 0;
        int bestTotal = 0;
        // 화로의 연료 칸에는 한 종류만 들어가므로, 판자를 가장 많이 채울 수 있는 나무 종류를 고른다.
        for (Material planks : Tag.PLANKS.getValues()) {
            int owned = stock.getOrDefault(planks, 0);
            if (owned >= needed) return;
            int amount = craftablePlanks(ai, stock, planks, FuelMath.planksToCraft(needed, owned));
            if (owned + amount <= bestTotal) continue;
            bestTotal = owned + amount;
            bestPlanks = planks;
            bestAmount = amount;
        }
        if (bestPlanks != null && bestAmount > 0) actions.add(new CraftItemAction(bestPlanks, bestAmount));
    }

    // 가진 원목으로 그 판자를 wanted 개까지 만들 수 있는 개수 (원목 하나에 4개씩).
    private static int craftablePlanks(AIPlayer ai, Map<Material, Integer> stock, Material planks, int wanted) {
        // 그 나무의 원목이 하나도 없으면 더 따져 보지 않는다.
        if (!ai.getCrafting().plan(planks, FuelMath.PLANKS_PER_LOG, stock).isFeasible()) return 0;
        for (int amount = wanted; amount > FuelMath.PLANKS_PER_LOG; amount -= FuelMath.PLANKS_PER_LOG) {
            if (ai.getCrafting().plan(planks, amount, stock).isFeasible()) return amount;
        }
        return FuelMath.PLANKS_PER_LOG;
    }
}
