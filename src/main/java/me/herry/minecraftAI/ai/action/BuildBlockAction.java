package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.build.BlockRole;
import me.herry.minecraftAI.ai.build.BuildMaterials;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 설계도의 한 칸을 짓는다: 정해진 자리에, 그 역할에 맞는 블록을 가방에서 골라 놓는다.
 * 벽과 지붕은 가진 돌이나 흙으로, 문/침대처럼 두 칸짜리는 두 칸을 함께 놓는다.
 */
public final class BuildBlockAction extends AbstractAction {
    private static final int TIMEOUT = 60;
    private static final double MAX_REACH = 4.9;
    private static final float FACING_TOLERANCE = 25.0F;
    private static final int MAX_AIM_TICKS = 8;
    private static final int MIN_PLACE_TICKS = 4;

    private final BlockPoint target;
    private final BlockRole role;
    // 문이 바깥을 보는 방향, 침대의 발치에서 머리 쪽 방향, 상자와 화로의 앞면 방향
    private final BlockFace facing;
    private World world;

    public BuildBlockAction(BlockPoint target, BlockRole role, BlockFace facing) {
        super("BuildBlock", TIMEOUT);
        this.target = target;
        this.role = role;
        this.facing = facing;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        int slot = BuildMaterials.slotFor(ai.getPlayer().getInventory(), role);
        if (slot < 0) {
            fail("no material for " + role);
            return;
        }
        ai.getInventory().equipSlot(slot);
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, target)) {
            fail("world changed");
            return;
        }
        Block block = Positions.block(world, target);
        if (BuildMaterials.isDone(block.getType(), role)) {
            succeed();
            return;
        }

        Location center = Positions.center(world, target);
        if (player.getEyeLocation().distance(center) > MAX_REACH) {
            fail("out of reach");
            return;
        }
        Block second = secondBlock(block);
        if (!BlockPlacing.canPlaceAt(block) || second != null && !BlockPlacing.canPlaceAt(second)) {
            fail("cannot place there");
            return;
        }

        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        boolean aimed = ai.getBody().isFacing(center.getX(), center.getY(), center.getZ(), FACING_TOLERANCE);
        // 이미 그쪽을 보고 있어도 사람이 블록을 놓는 속도보다 빠르게 놓지는 않는다.
        if (getElapsed() < MIN_PLACE_TICKS || !aimed && getElapsed() < MAX_AIM_TICKS) return;

        Material item = player.getInventory().getItemInMainHand().getType();
        if (!BuildMaterials.fits(item, role)) {
            fail("no material for " + role);
            return;
        }
        BlockData data = dataFor(item, false);
        boolean placed = second == null
                ? BlockPlacing.place(player, block, data, null, null)
                : BlockPlacing.place(player, block, data, second, dataFor(item, true));
        if (!placed) {
            fail("place denied");
            return;
        }
        remember(ai);
        succeed();
    }

    // 문은 위 칸, 침대는 머리 쪽 칸을 함께 차지한다.
    private @Nullable Block secondBlock(Block block) {
        if (role == BlockRole.DOOR) return block.getRelative(BlockFace.UP);
        if (role == BlockRole.BED) return block.getRelative(facing);
        return null;
    }

    private BlockData dataFor(Material item, boolean second) {
        BlockData data = item.createBlockData();
        if (data instanceof Door door) {
            door.setFacing(facing);
            door.setHalf(second ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        } else if (data instanceof Bed bed) {
            bed.setFacing(facing);
            bed.setPart(second ? Bed.Part.HEAD : Bed.Part.FOOT);
        } else if (data instanceof Directional directional && directional.getFaces().contains(facing)) {
            directional.setFacing(facing);
        }
        return data;
    }

    // 들여놓은 시설의 위치를 거점 정보와 기억에 남긴다.
    private void remember(AIPlayer ai) {
        Base home = ai.getWorldModel().homeIn(world.getUID());
        long now = ai.getTicks();
        switch (role) {
            case WORKBENCH -> {
                // 집의 작업대는 "직접 놓아서 챙겨 갈 작업대"로 기억하지 않는다. 집에 그대로 둔다.
                ai.getMemory().rememberPermanent(MemoryType.WORKBENCH, world.getUID(), target, now);
                if (home != null) home.setWorkbench(target);
            }
            case FURNACE -> {
                ai.getMemory().rememberPermanent(MemoryType.FURNACE, world.getUID(), target, now);
                if (home != null) home.setFurnace(target);
            }
            case CHEST -> {
                if (home != null) home.addChest(target);
                ai.getWorldModel().rememberContents(world.getUID(), target, Map.of());
            }
            case BED -> {
                ai.getMemory().rememberPermanent(MemoryType.BED, world.getUID(), target, now);
                if (home != null) home.setBed(target);
            }
            case DOOR -> {
                if (home != null) home.setEntrance(target);
            }
            default -> {
            }
        }
    }
}
