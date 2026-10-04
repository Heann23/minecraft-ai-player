package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 물의 원천 블록에 가진 블록을 채워 넣어서 물길을 막는다.
 */
public final class PlugWaterAction extends AbstractAction {
    private static final int TIMEOUT = 60;
    private static final double MAX_REACH = 4.9;
    private static final float FACING_TOLERANCE = 25.0F;
    private static final int MAX_AIM_TICKS = 12;

    private final BlockPoint source;
    private World world;

    public PlugWaterAction(BlockPoint source) {
        super("PlugWater", TIMEOUT);
        this.source = source;
    }

    // 물을 막는 데 쓸 만한 블록인지. 돌, 흙처럼 흔하고 꽉 찬 블록만 쓴다.
    public static boolean isFiller(Material material) {
        return InventorySystem.isFiller(material);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        int slot = ai.getInventory().findFillerSlot();
        if (slot < 0) {
            fail("no block to plug with");
            return;
        }
        ai.getInventory().equipSlot(slot);
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, source)) {
            fail("world changed");
            return;
        }
        Block block = Positions.block(world, source);
        if (block.getType() != Material.WATER) {
            // 이미 물이 사라졌으면 할 일이 없다.
            succeed();
            return;
        }

        Location center = Positions.center(world, source);
        if (player.getEyeLocation().distance(center) > MAX_REACH) {
            fail("out of reach");
            return;
        }
        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        ai.getBody().inputJump(player.isInWater());
        if (!ai.getBody().isFacing(center.getX(), center.getY(), center.getZ(), FACING_TOLERANCE) && getElapsed() < MAX_AIM_TICKS) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isFiller(hand.getType())) {
            fail("no block to plug with");
            return;
        }

        BlockData data = hand.getType().createBlockData();
        BlockState replaced = block.getState();
        block.setBlockData(data, true);
        BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, block.getRelative(BlockFace.DOWN), hand, player, true, EquipmentSlot.HAND);
        if (!event.callEvent() || !event.canBuild()) {
            replaced.update(true, false);
            fail("place denied");
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        player.swingMainHand();
        world.playSound(block.getLocation(), data.getSoundGroup().getPlaceSound(), 1.0F, 1.0F);
        succeed();
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputJump(false);
    }
}
