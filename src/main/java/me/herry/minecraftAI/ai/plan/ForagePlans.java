package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.HarvestFoodAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.survival.FoodSources;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Existing visible food resources; no undiscovered plants, private chests or forced crop growth. */
public final class ForagePlans {
    private static final double SOURCE_RANGE = 32.0;
    private static final double REACH = 3.75;

    private ForagePlans() {}

    public static boolean hasSource(AIPlayer ai) { return locate(ai) != null; }

    public static @Nullable Material product(AIPlayer ai) {
        BlockPoint source = locate(ai);
        return source == null ? null : FoodSources.product(Positions.block(ai.getPlayer().getWorld(), source).getType());
    }

    public static @Nullable BlockPoint locate(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        var eye = ai.getPlayer().getEyeLocation();
        var opacity = PerceptionSystem.opacityOf(world);
        BlockPoint best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (var entry : ai.getMemory().all(MemoryType.FOOD_SOURCE, world.getUID(), ai.getTicks())) {
            if (ai.getWorkBudget().isExhausted()) break;
            BlockPoint source = entry.pos();
            double distance = source.distance(ai.getPosition());
            if (distance > SOURCE_RANGE || !Positions.isLoaded(world, source)) continue;
            var block = Positions.block(world, source);
            if (!FoodSources.isReady(block)) {
                ai.getMemory().forget(MemoryType.FOOD_SOURCE, world.getUID(), source);
                continue;
            }
            if (ai.getMemory().contains(MemoryType.UNREACHABLE, world.getUID(), source, ai.getTicks())
                    || ai.getTeam().isClaimedByOther(ai, world.getUID(), source)) continue;
            if (!Visibility.canSeeBlock(opacity, eye.getX(), eye.getY(), eye.getZ(), source.x(), source.y(), source.z())) continue;
            Material product = FoodSources.product(block.getType());
            double cost = distance + (product != null && product.isEdible() ? 0.0 : 8.0);
            if (cost < bestCost) { best = source; bestCost = cost; }
        }
        return best;
    }

    public static List<Action> gather(AIPlayer ai) {
        BlockPoint source = locate(ai);
        return source == null ? List.of() : gather(ai, source);
    }

    static List<Action> gather(AIPlayer ai, BlockPoint source) {
        if (!Positions.isLoaded(ai.getPlayer().getWorld(), source)
                || !FoodSources.isReady(Positions.block(ai.getPlayer().getWorld(), source))) return List.of();
        ai.getTeam().claim(ai, source);
        return List.of(new MoveToAction(PathGoal.reach(source, REACH), false), new HarvestFoodAction(source),
                new PickupItemAction(6.0, false));
    }
}
