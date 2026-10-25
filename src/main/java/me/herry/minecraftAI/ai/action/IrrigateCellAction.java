package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.farming.FarmChecks;
import me.herry.minecraftAI.ai.farming.IrrigationRules;
import me.herry.minecraftAI.ai.plan.IrrigationPlans;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.ToolChoice;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One contained source, with normal exact mining and bucket use. Partial excavation is never rolled back by world edits. */
public final class IrrigateCellAction extends AbstractAction implements PrimitiveAction {
    private final UUID targetWorld;
    private final BlockPoint cell;
    private World world;
    private Action excavation;
    private Material initial;
    private boolean used;
    private int usedAt;

    public IrrigateCellAction(UUID targetWorld, BlockPoint cell) {
        super("IrrigateCell", 700);
        this.targetWorld = Objects.requireNonNull(targetWorld);
        this.cell = Objects.requireNonNull(cell);
    }

    @Override protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        FarmChecks.stop(ai);
        if (!world.getUID().equals(targetWorld)) { fail("world changed"); return; }
        String reason = IrrigationPlans.validate(ai, cell, true);
        if (!reason.isEmpty()) { fail(reason); return; }
        initial = Positions.block(world, cell).getType();
        if (IrrigationPlans.sourcePresent(ai, cell)) { IrrigationPlans.rememberSource(ai, cell); succeed(); return; }
        if (!ai.getInventory().has(Material.WATER_BUCKET)) { fail("water bucket missing"); return; }
        if (IrrigationRules.excavatable(initial)) {
            int slot = ai.getInventory().bestToolSlot(Positions.block(world, cell));
            if (slot < 0) { ai.getInventory().restHand(); slot = ai.getPlayer().getInventory().getHeldItemSlot(); }
            ItemStack stack = ai.getPlayer().getInventory().getItem(slot);
            Map<String, Integer> enchantments = new HashMap<>();
            if (stack != null && !stack.isEmpty()) stack.getEnchantments().forEach((key, level) -> enchantments.put(key.getKey().toString(), level));
            excavation = BreakBlockAction.withTool(cell, new ToolChoice(slot,
                    stack == null || stack.isEmpty() ? "AIR" : stack.getType().name(), enchantments));
        }
    }

    @Override protected void onTick(AIPlayer ai) {
        if (!world.equals(ai.getPlayer().getWorld()) || !ai.getBody().isUsable()) { fail("body unavailable or world changed"); return; }
        String reason = IrrigationPlans.validate(ai, cell, true);
        if (!reason.isEmpty()) { fail(reason); return; }
        if (IrrigationPlans.sourcePresent(ai, cell)) { IrrigationPlans.rememberSource(ai, cell); succeed(); return; }
        if (excavation != null) {
            if (Positions.block(world, cell).getType() != initial) { fail("excavation target changed"); return; }
            excavation.update(ai);
            if (excavation.getStatus() == ActionStatus.FAILED) fail(excavation.getFailReason());
            else if (excavation.getStatus() == ActionStatus.SUCCESS) excavation = null;
            return;
        }
        if (!Positions.block(world, cell).getType().isAir()) { fail("basin occupied"); return; }
        if (used) { if (getElapsed() - usedAt >= 30) fail("water source not observed"); return; }
        if (!ai.getInventory().equip(Material.WATER_BUCKET)) { fail("water bucket missing"); return; }
        BlockPoint support = cell.offset(0, -1, 0);
        Location target = new Location(world, cell.x() + 0.5, cell.y(), cell.z() + 0.5);
        ai.getBody().lookAt(target.getX(), target.getY(), target.getZ());
        if (!ai.getBody().isFacing(target.getX(), target.getY(), target.getZ(), 2.0F)) return;
        var eye = ai.getPlayer().getEyeLocation();
        var direction = target.toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (distance <= 0 || distance > 4.5) { fail("basin floor out of reach"); return; }
        var hit = world.rayTraceBlocks(eye, direction.normalize(), distance + 0.05, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null || !Positions.of(hit.getHitBlock()).equals(support)
                || hit.getHitBlockFace() != BlockFace.UP) { fail("basin floor face not visible"); return; }
        used = true; usedAt = getElapsed();
        if (!ai.getBody().useItemOn(support, BlockFace.UP)) { fail("water placement denied"); return; }
        ai.getPlayer().swingMainHand();
    }

    @Override protected void onEnd(AIPlayer ai) {
        if (excavation != null && excavation.getStatus() == ActionStatus.RUNNING) excavation.cancel(ai);
        if (world != null && world.getUID().equals(ai.getWorldId()) && ai.getBody().isUsable()
                && cell.distance(ai.getPosition()) <= 5.0 && IrrigationPlans.sourcePresent(ai, cell)) IrrigationPlans.rememberSource(ai, cell);
        IrrigationPlans.invalidate(ai);
        FarmChecks.stop(ai);
    }

    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.INTERACT_BLOCK; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Block(cell); }
}
