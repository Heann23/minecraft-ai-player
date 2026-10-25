package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.farming.CropRules;
import me.herry.minecraftAI.ai.farming.FarmChecks;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.plan.FarmingPlans;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.Objects;

/** Places one propagation item with a real right click and observes the actual resulting crop. */
public final class PlantCropAction extends AbstractAction implements PrimitiveAction {
    private final BlockPoint soil;
    private final Material seed;
    private final Material crop;
    private World world;
    private boolean used;

    public PlantCropAction(BlockPoint soil, Material seed) {
        super("PlantCrop", 60);
        this.soil = Objects.requireNonNull(soil);
        this.seed = Objects.requireNonNull(seed);
        this.crop = Objects.requireNonNull(CropRules.crop(seed), "unsupported propagation item");
    }

    @Override protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        FarmChecks.stop(ai);
        if (!FarmingPlans.allowedToPlant(ai, seed)) { fail("propagation reserve required"); return; }
        if (!ai.getInventory().equip(seed)) fail("propagation item missing");
    }

    @Override protected void onTick(AIPlayer ai) {
        if (!world.equals(ai.getPlayer().getWorld()) || !Positions.isLoaded(world, soil)
                || !Positions.isLoaded(world, soil.offset(0, 1, 0))) { fail("plot unavailable"); return; }
        Material actual = Positions.block(world, soil.offset(0, 1, 0)).getType();
        if (used && actual == crop && Positions.block(world, soil).getType() == Material.FARMLAND) {
            ai.getMemory().rememberPermanent(MemoryType.FARM_PLOT, world.getUID(), soil, ai.getTicks());
            ai.getMemory().remember(MemoryType.FARM_CHECKED, world.getUID(), soil, ai.getTicks(), 1000);
            FarmingPlans.invalidate(ai);
            succeed(); return;
        }
        if (used) { if (getElapsed() >= 40) fail("planting made no crop"); return; }
        String reason = FarmChecks.unavailable(ai, world, soil);
        if (!reason.isEmpty()) { fail(reason); return; }
        if (Positions.block(world, soil).getType() != Material.FARMLAND) { fail("farmland required"); return; }
        if (!FarmingPlans.allowedToPlant(ai, seed)) { fail("propagation reserve changed"); return; }
        var top = FarmChecks.top(world, soil);
        ai.getBody().lookAt(top.getX(), top.getY(), top.getZ());
        if (!ai.getBody().isFacing(top.getX(), top.getY(), top.getZ(), 10.0F)) return;
        if (!FarmChecks.visibleTop(ai, soil)) { fail("farmland top not visible"); return; }
        if (ai.getPlayer().getInventory().getItemInMainHand().getType() != seed) { fail("held propagation item changed"); return; }
        used = true;
        if (!ai.getBody().useItemOn(soil, BlockFace.UP)) { fail("planting denied"); return; }
        ai.getPlayer().swingMainHand();
    }

    @Override protected void onEnd(AIPlayer ai) {
        // The right click may have planted before an emergency/stop cancelled this action.
        // Keep observed production state while leaving the action's cancelled/failed status intact.
        if (used && world != null && ai.getBody().isUsable() && world.equals(ai.getPlayer().getWorld())
                && Positions.isLoaded(world, soil) && Positions.isLoaded(world, soil.offset(0, 1, 0))
                && Positions.block(world, soil).getType() == Material.FARMLAND
                && Positions.block(world, soil.offset(0, 1, 0)).getType() == crop
                && ai.getPlayer().getEyeLocation().distance(Positions.center(world, soil.offset(0, 1, 0))) <= FarmChecks.REACH
                && FarmChecks.soilVisible(ai, soil)) {
            ai.getMemory().rememberPermanent(MemoryType.FARM_PLOT, world.getUID(), soil, ai.getTicks());
            ai.getMemory().remember(MemoryType.FARM_CHECKED, world.getUID(), soil, ai.getTicks(), 1000);
            FarmingPlans.invalidate(ai);
        }
        FarmChecks.stop(ai);
    }
    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.INTERACT_BLOCK; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Block(soil.offset(0, 1, 0)); }
}
