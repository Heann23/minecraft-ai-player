package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 제자리에서 뛰어올라 발밑에 블록을 놓아서 한 칸 올라선다. 높은 나무 위쪽을 캐거나, 걸어서 나갈 수 없는 굴에서 올라올 때 쓴다.
 * 머리 위가 막혀 있으면 먼저 캐서 올라설 자리를 만든다.
 */
public final class PillarUpAction extends AbstractAction {
    private static final int TIMEOUT = 300;
    // 뛰어오른 뒤 이 시간 안에 블록을 놓지 못하면 실패로 본다.
    private static final int MAX_JUMP_TICKS = 30;
    private static final double PLACE_CLEARANCE = 1.02;
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP};

    private final @Nullable Consumer<BlockPoint> onPlaced;
    private World world;
    private BlockPoint start;
    private BreakBlockAction clearing;
    private int jumpTicks;
    private boolean placed;

    /**
     * @param onPlaced 블록을 놓은 칸을 알려 받는다 (나중에 다시 캐서 내려오기 위해). 필요 없으면 null.
     */
    public PillarUpAction(@Nullable Consumer<BlockPoint> onPlaced) {
        super("PillarUp", TIMEOUT);
        this.onPlaced = onPlaced;
    }

    /**
     * 지금 자리에서 한 칸 쌓아 올라갈 수 있는지 미리 확인한다. 머리 위의 블록을 캐야 한다면 그것도 안전해야 한다.
     */
    public static boolean canPillar(AIPlayer ai) {
        if (!ai.getBody().isGrounded() || ai.getInventory().findFillerSlot() < 0) return false;
        Player player = ai.getPlayer();
        World world = player.getWorld();
        BlockPoint feet = ai.getPosition();
        if (feet.y() + 3 >= world.getMaxHeight()) return false;
        return isSafeCeiling(world, feet.offset(0, 2, 0));
    }

    @Override
    protected void onStart(AIPlayer ai) {
        Player player = ai.getPlayer();
        world = player.getWorld();
        start = ai.getPosition();
        if (!ai.getBody().isGrounded()) {
            fail("not on ground");
            return;
        }
        int slot = ai.getInventory().findFillerSlot();
        if (slot < 0) {
            fail("no blocks to place");
            return;
        }
        BlockPoint ceiling = start.offset(0, 2, 0);
        if (!isSafeCeiling(world, ceiling)) {
            fail("unsafe ceiling");
            return;
        }
        if (BukkitTerrainView.classify(Positions.block(world, ceiling).getType()) != BlockClass.OPEN) {
            clearing = new BreakBlockAction(ceiling);
        }
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world)) {
            fail("world changed");
            return;
        }

        if (clearing != null) {
            clearing.update(ai);
            if (clearing.getStatus() == ActionStatus.SUCCESS) {
                clearing = null;
            } else if (clearing.getStatus() == ActionStatus.FAILED) {
                fail("ceiling: " + clearing.getFailReason());
            }
            return;
        }

        if (placed) {
            ai.getBody().inputJump(false);
            if (ai.getBody().isGrounded() && Positions.feet(player.getLocation()).y() > start.y()) succeed();
            return;
        }

        int slot = ai.getInventory().findFillerSlot();
        if (slot < 0) {
            fail("no blocks to place");
            return;
        }
        ai.getInventory().equipSlot(slot);
        ai.getBody().inputLook(player.getLocation().getYaw(), 90.0F);
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputJump(true);

        // 발이 원래 칸보다 충분히 올라가서 그 칸이 비었을 때 블록을 놓는다.
        if (player.getLocation().getY() >= start.y() + PLACE_CLEARANCE) {
            Block block = Positions.block(world, start);
            // 어두워서 발밑에 꽂아 둔 횃불이 있으면 그 칸에 블록을 놓을 수 없다. 횃불은 한 번 치면 뽑히므로 뽑고 놓는다.
            if (block.getType() == Material.TORCH) player.breakBlock(block);
            if (!BlockPlacing.canPlaceAt(block) || !InventorySystem.isFiller(player.getInventory().getItemInMainHand().getType())) {
                fail("cannot place below");
                return;
            }
            if (!BlockPlacing.place(player, block)) {
                fail("place denied");
                return;
            }
            placed = true;
            ai.getBody().inputJump(false);
            if (onPlaced != null) onPlaced.accept(start);
            return;
        }
        if (++jumpTicks > MAX_JUMP_TICKS) fail("could not jump");
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        if (clearing != null) clearing.cancel(ai);
        ai.getBody().inputJump(false);
    }

    // 머리 위 칸을 캐도 물, 용암, 떨어지는 모래나 자갈이 쏟아지지 않는지.
    private static boolean isSafeCeiling(World world, BlockPoint ceiling) {
        if (!Positions.isLoaded(world, ceiling)) return false;
        Block block = Positions.block(world, ceiling);
        Material type = block.getType();
        if (block.isLiquid() || type.hasGravity() || type.getHardness() < 0.0F) return false;
        for (BlockFace face : SIDES) {
            Block neighbor = block.getRelative(face);
            if (neighbor.isLiquid()) return false;
            if (face == BlockFace.UP && neighbor.getType().hasGravity()) return false;
        }
        return true;
    }
}
