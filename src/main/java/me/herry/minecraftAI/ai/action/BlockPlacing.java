package me.herry.minecraftAI.ai.action;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

/**
 * 손에 든 블록을 실제 플레이어가 놓을 때와 같은 절차로 설치한다.
 */
final class BlockPlacing {
    private static final BlockFace[] FACES = {BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP};

    private BlockPlacing() {
    }

    // 블록을 놓을 수 있는 칸인지: 비어 있거나(풀, 물 등 덮어쓸 수 있는 것 포함) 용암이 아니고, 붙일 면이 있고, 엔티티가 없어야 한다.
    static boolean canPlaceAt(Block block) {
        if (!(block.isEmpty() || block.isReplaceable())) return false;
        if (block.getType() == Material.LAVA) return false;
        if (supportOf(block) == null) return false;
        return !hasBlockingEntity(block);
    }

    /**
     * 손에 든 아이템을 block 자리에 놓는다. 보호 플러그인이 막으면 원래대로 되돌리고 false.
     */
    static boolean place(Player player, Block block) {
        return place(player, block, supportOf(block));
    }

    // Exact primitives already checked the particular support face the player can see.
    static boolean place(Player player, Block block, Block against) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty() || !hand.getType().isBlock()) return false;
        if (against == null || !against.getType().isSolid() || block.getFace(against) == null) return false;

        BlockData data = hand.getType().createBlockData();
        BlockState replaced = block.getState();
        block.setBlockData(data, true);
        BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, against, hand, player, true, EquipmentSlot.HAND);
        if (!event.callEvent() || !event.canBuild()) {
            replaced.update(true, false);
            return false;
        }

        hand.setAmount(hand.getAmount() - 1);
        player.swingMainHand();
        World world = block.getWorld();
        world.playSound(block.getLocation(), data.getSoundGroup().getPlaceSound(), 1.0F, 1.0F);
        return true;
    }

    /**
     * 손에 든 아이템을 정해진 모양(data)으로 놓는다. 문이나 침대처럼 두 칸을 차지하는 것은 둘째 칸도 함께 놓는다.
     * 보호 플러그인이 막으면 두 칸 모두 원래대로 되돌리고 false.
     *
     * @param second     둘째 칸 (문의 위쪽, 침대의 머리 쪽). 한 칸짜리면 null.
     * @param secondData 둘째 칸의 모양
     */
    static boolean place(Player player, Block block, BlockData data, Block second, BlockData secondData) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty()) return false;
        Block against = supportOf(block);
        if (against == null) return false;

        BlockState replaced = block.getState();
        BlockState replacedSecond = second == null ? null : second.getState();
        // 두 칸짜리는 서로가 있어야 유지된다. 한 칸만 놓인 순간에 물리 갱신이 돌면 부서지므로 갱신 없이 둘 다 놓는다.
        block.setBlockData(data, second == null);
        if (second != null) second.setBlockData(secondData, false);
        BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, against, hand, player, true, EquipmentSlot.HAND);
        if (!event.callEvent() || !event.canBuild()) {
            if (replacedSecond != null) replacedSecond.update(true, false);
            replaced.update(true, false);
            return false;
        }

        hand.setAmount(hand.getAmount() - 1);
        player.swingMainHand();
        block.getWorld().playSound(block.getLocation(), data.getSoundGroup().getPlaceSound(), 1.0F, 1.0F);
        return true;
    }

    // 블록을 붙일 수 있는 이웃 블록. 실제 플레이어도 허공에는 블록을 놓을 수 없다.
    private static Block supportOf(Block block) {
        for (BlockFace face : FACES) {
            Block neighbor = block.getRelative(face);
            if (neighbor.getType().isSolid()) return neighbor;
        }
        return null;
    }

    /**
     * 몹이나 플레이어(자기 자신 포함), 탈것처럼 몸이 있는 엔티티가 그 칸에 있으면 블록을 놓을 수 없다.
     * 떨어진 아이템, 경험치, 화살, 그리고 머리 위의 목표 표시(TextDisplay)처럼 몸이 없는 것은 막지 않는다.
     * 표시 엔티티까지 막는 것으로 치면 AI 가 자기 머리 위 칸(지붕)에 블록을 놓지 못한다.
     */
    static boolean hasBlockingEntity(Block block) {
        for (Entity entity : block.getWorld().getNearbyEntities(BoundingBox.of(block))) {
            if (entity instanceof LivingEntity || entity instanceof Vehicle || entity instanceof FallingBlock
                    || entity instanceof EnderCrystal) return true;
        }
        return false;
    }
}
