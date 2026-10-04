package me.herry.minecraftAI.ai.survival;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
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
 * 어두운 곳에 횃불을 놓는다. 동굴이나 직접 판 굴처럼 하늘이 보이지 않는 곳에서만 놓고,
 * 밤에 지상을 돌아다닐 때는 놓지 않는다. 하늘빛은 밤에도 "하늘이 보이는 정도"로 남아 있어서 이것으로 둘을 구분한다.
 */
public final class LightingSystem {
    // 몬스터는 밝기 0 에서 생기지만, 횃불 사이가 너무 벌어지지 않도록 이 밝기 이하면 어둡다고 본다.
    private static final int DARK_LEVEL = 7;
    private static final long MIN_INTERVAL = 40L;

    private long lastPlacedTick = -MIN_INTERVAL;

    /**
     * 발밑이 어둡고 횃불이 있으면 서 있는 자리에 하나 놓는다. 판단 주기마다 호출한다.
     */
    public void tick(AIPlayer ai) {
        if (ai.getTicks() - lastPlacedTick < MIN_INTERVAL || !ai.getBody().isGrounded()) return;
        int slot = ai.getInventory().findSlot(item -> item.getType() == Material.TORCH);
        if (slot < 0) return;

        Player player = ai.getPlayer();
        World world = player.getWorld();
        BlockPoint feet = Positions.feet(player.getLocation());
        if (feet.y() <= world.getMinHeight() || feet.y() >= world.getMaxHeight()) return;

        Block block = Positions.block(world, feet);
        if (!block.getType().isAir() || block.getLightFromSky() > DARK_LEVEL || block.getLightFromBlocks() > DARK_LEVEL) return;

        Block below = block.getRelative(BlockFace.DOWN);
        BlockData torch = Material.TORCH.createBlockData();
        if (!below.getType().isSolid() || !block.canPlace(torch)) return;

        ItemStack item = player.getInventory().getItem(slot);
        if (item == null || item.isEmpty()) return;
        BlockState replaced = block.getState();
        block.setBlockData(torch, true);
        BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, below, item, player, true, EquipmentSlot.HAND);
        if (!event.callEvent() || !event.canBuild()) {
            replaced.update(true, false);
            return;
        }

        item.setAmount(item.getAmount() - 1);
        player.swingMainHand();
        world.playSound(block.getLocation(), torch.getSoundGroup().getPlaceSound(), 1.0F, 1.0F);
        lastPlacedTick = ai.getTicks();
        ai.debug("Placed a torch at " + feet);
    }
}
