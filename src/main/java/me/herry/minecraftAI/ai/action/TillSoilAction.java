package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.farming.CropRules;
import me.herry.minecraftAI.ai.farming.FarmChecks;
import me.herry.minecraftAI.ai.inventory.ToolTier;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.plan.FarmingPlans;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.Comparator;

/** One observed dirt-to-farmland interaction through the player's held hoe. */
public final class TillSoilAction extends AbstractAction implements PrimitiveAction {
    private final BlockPoint soil;
    private World world;
    private Material hoe;
    private boolean used;

    public TillSoilAction(BlockPoint soil) { super("TillSoil", 60); this.soil = soil; }

    @Override protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        FarmChecks.stop(ai);
        hoe = ai.getInventory().snapshot().keySet().stream().filter(Tag.ITEMS_HOES::isTagged)
                .min(Comparator.comparingInt((Material material) -> ToolTier.of(material).level()).thenComparing(Material::name)).orElse(null);
        if (hoe == null || !ai.getInventory().equip(hoe)) fail("no hoe");
    }

    @Override protected void onTick(AIPlayer ai) {
        if (!world.equals(ai.getPlayer().getWorld()) || !Positions.isLoaded(world, soil)) { fail("plot unavailable"); return; }
        if (Positions.block(world, soil).getType() == Material.FARMLAND) {
            if (used) {
                ai.getMemory().rememberPermanent(MemoryType.FARM_PLOT, world.getUID(), soil, ai.getTicks());
                FarmingPlans.invalidate(ai);
            }
            succeed(); return;
        }
        String reason = FarmChecks.unavailable(ai, world, soil);
        if (!reason.isEmpty()) { fail(reason); return; }
        if (!CropRules.tillable(Positions.block(world, soil).getType())) { fail("soil changed"); return; }
        if (used) { if (getElapsed() >= 40) fail("tilling made no farmland"); return; }
        var top = FarmChecks.top(world, soil);
        ai.getBody().lookAt(top.getX(), top.getY(), top.getZ());
        if (!ai.getBody().isFacing(top.getX(), top.getY(), top.getZ(), 10.0F)) return;
        if (!FarmChecks.visibleTop(ai, soil)) { fail("soil top not visible"); return; }
        if (ai.getPlayer().getInventory().getItemInMainHand().getType() != hoe) { fail("held hoe changed"); return; }
        used = true;
        if (!ai.getBody().useItemOn(soil, BlockFace.UP)) { fail("tilling denied"); return; }
        ai.getPlayer().swingMainHand();
    }

    @Override protected void onEnd(AIPlayer ai) {
        // A cancelled action can still have spent durability and really tilled the soil.
        // Preserve only that visible result; cancellation never turns into action success.
        if (used && world != null && ai.getBody().isUsable() && world.equals(ai.getPlayer().getWorld())
                && Positions.isLoaded(world, soil) && Positions.isLoaded(world, soil.offset(0, 1, 0))
                && Positions.block(world, soil).getType() == Material.FARMLAND
                && ai.getPlayer().getEyeLocation().distance(Positions.center(world, soil)) <= FarmChecks.REACH
                && FarmChecks.soilVisible(ai, soil)) {
            ai.getMemory().rememberPermanent(MemoryType.FARM_PLOT, world.getUID(), soil, ai.getTicks());
            FarmingPlans.invalidate(ai);
        }
        FarmChecks.stop(ai);
    }
    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.INTERACT_BLOCK; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Block(soil); }
}
