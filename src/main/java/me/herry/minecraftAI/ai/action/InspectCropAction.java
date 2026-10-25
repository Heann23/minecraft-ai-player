package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.farming.FarmChecks;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.plan.FarmingPlans;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.survival.FoodSources;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.World;

/** Records an actually inspected plot, not a promised growth time or harvest quantity. */
public final class InspectCropAction extends AbstractAction implements PrimitiveAction {
    private static final long RECHECK_TICKS = 1000;
    private final BlockPoint soil;
    private World world;

    public InspectCropAction(BlockPoint soil) { super("InspectCrop", 60); this.soil = soil; }

    @Override protected void onStart(AIPlayer ai) { world = ai.getPlayer().getWorld(); FarmChecks.stop(ai); }

    @Override protected void onTick(AIPlayer ai) {
        if (ai.getWorkBudget().isExhausted()) return;
        BlockPoint target = soil.offset(0, 1, 0);
        if (!world.equals(ai.getPlayer().getWorld()) || !Positions.isLoaded(world, soil)
                || !Positions.isLoaded(world, target)) { fail("plot unavailable"); return; }
        if (!ai.getMemory().contains(MemoryType.FARM_PLOT, world.getUID(), soil, ai.getTicks())) { fail("unknown owned plot"); return; }
        var eye = ai.getPlayer().getEyeLocation();
        if (eye.distance(Positions.center(world, target)) > FarmChecks.REACH) { fail("plot out of observation range"); return; }
        var opacity = PerceptionSystem.opacityOf(world);
        if (!Visibility.canSeeBlock(opacity, eye.getX(), eye.getY(), eye.getZ(), target.x(), target.y(), target.z())
                && !Visibility.canSeeBlock(opacity, eye.getX(), eye.getY(), eye.getZ(), soil.x(), soil.y(), soil.z())) {
            fail("plot not visible"); return;
        }
        var point = Positions.center(world, target);
        ai.getBody().lookAt(point.getX(), point.getY(), point.getZ());
        if (!ai.getBody().isFacing(point.getX(), point.getY(), point.getZ(), 15.0F)) return;
        if (FoodSources.isReady(Positions.block(world, target))) {
            ai.getMemory().remember(MemoryType.FOOD_SOURCE, world.getUID(), target, ai.getTicks(), 6000);
        } else ai.getMemory().forget(MemoryType.FOOD_SOURCE, world.getUID(), target);
        ai.getMemory().remember(MemoryType.FARM_CHECKED, world.getUID(), soil, ai.getTicks(), RECHECK_TICKS);
        FarmingPlans.invalidate(ai);
        succeed();
    }

    @Override protected void onEnd(AIPlayer ai) { FarmChecks.stop(ai); }
    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.INTERACT_BLOCK; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Block(soil.offset(0, 1, 0)); }
}
