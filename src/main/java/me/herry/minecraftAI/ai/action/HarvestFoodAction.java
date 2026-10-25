package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.ToolChoice;
import me.herry.minecraftAI.ai.survival.FoodSources;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Revalidates maturity, then uses the normal input/mining path; pickup remains a separate action. */
public final class HarvestFoodAction extends AbstractAction implements PrimitiveAction {
    private static final double REACH = 4.9;
    private final BlockPoint target;
    private World world;
    private Material expected;
    private Action mining;
    private boolean used;

    public HarvestFoodAction(BlockPoint target) {
        super("HarvestFood", 620);
        this.target = target;
    }

    @Override protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        if (!Positions.isLoaded(world, target)) { fail("chunk not loaded"); return; }
        Block block = Positions.block(world, target);
        expected = block.getType();
        if (!FoodSources.isReady(block)) { fail("food source not mature"); return; }
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
        ai.getBody().inputSneak(false);
        if (!FoodSources.usesInteraction(expected)) {
            int slot = ai.getInventory().bestToolSlot(block);
            if (slot < 0) {
                ai.getInventory().restHand();
                slot = ai.getPlayer().getInventory().getHeldItemSlot();
            }
            ItemStack stack = ai.getPlayer().getInventory().getItem(slot);
            Map<String, Integer> enchantments = new HashMap<>();
            if (stack != null && !stack.isEmpty()) {
                stack.getEnchantments().forEach((enchantment, level) -> enchantments.put(enchantment.getKey().toString(), level));
            }
            mining = BreakBlockAction.withTool(target, new ToolChoice(slot,
                    stack == null || stack.isEmpty() ? "AIR" : stack.getType().name(), enchantments));
        } else ai.getInventory().restHand();
    }

    @Override protected void onTick(AIPlayer ai) {
        if (!world.equals(ai.getPlayer().getWorld()) || !Positions.isLoaded(world, target)) {
            fail("world changed or chunk unloaded"); return;
        }
        Block block = Positions.block(world, target);
        if (block.getType() != expected) { fail("food source changed"); return; }
        if (used && !FoodSources.isReady(block)) {
            ai.getMemory().forget(MemoryType.FOOD_SOURCE, world.getUID(), target);
            succeed();
            return;
        }
        if (!FoodSources.isReady(block)) { fail("food source no longer mature"); return; }
        var player = ai.getPlayer();
        var eye = player.getEyeLocation();
        if (eye.distance(Positions.center(world, target)) > REACH) { fail("out of reach"); return; }
        if (!Visibility.canSeeBlock(PerceptionSystem.opacityOf(world), eye.getX(), eye.getY(), eye.getZ(), target.x(), target.y(), target.z())) {
            fail("food source not visible"); return;
        }
        if (mining != null) {
            mining.update(ai);
            if (mining.getStatus() == ActionStatus.SUCCESS) {
                ai.getMemory().forget(MemoryType.FOOD_SOURCE, world.getUID(), target);
                succeed();
            } else if (mining.getStatus() == ActionStatus.FAILED) fail(mining.getFailReason());
            return;
        }
        var center = Positions.center(world, target);
        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        if (!ai.getBody().isFacing(center.getX(), center.getY(), center.getZ(), 15.0F)) return;
        if (!used) {
            used = true;
            if (!ai.getBody().useItemOn(target, BlockFace.UP)) { fail("harvest interaction denied"); return; }
            player.swingMainHand();
        } else if (getElapsed() >= 40) fail("harvest interaction made no change");
    }

    @Override protected void onEnd(AIPlayer ai) {
        if (mining != null && mining.getStatus() == ActionStatus.RUNNING) mining.cancel(ai);
        ai.getBody().inputJump(false);
        ai.getBody().inputSneak(false);
    }

    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.INTERACT_BLOCK; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Block(target); }
}
