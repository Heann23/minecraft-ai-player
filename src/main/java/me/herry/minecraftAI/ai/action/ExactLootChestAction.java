package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import me.herry.minecraftAI.ai.primitive.runtime.PrimitiveAdapter;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Objects;

/**
 * Opens a visible, reachable chest or barrel through the normal server block-interaction path
 * (preserving vanilla lid obstruction and loot-table generation) and transfers items that fit
 * into storage without auto-equipping armor or modifying unrelated state.
 */
public final class ExactLootChestAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 60;
    private static final int OPEN_TICKS = 4;

    private final PrimitiveCommand.LootChest command;
    private World world;
    private boolean opened;
    private int openedAt;

    public ExactLootChestAction(PrimitiveCommand.LootChest command) {
        super("ExactLootChest", TIMEOUT);
        this.command = Objects.requireNonNull(command, "command");
    }

    public static boolean isLootableContainer(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
    }

    public static BlockFace visibleHitFace(Player player, BlockPoint target, double fallbackHeight, boolean ignorePassable) {
        World world = player.getWorld();
        if (target.y() < world.getMinHeight() || target.y() >= world.getMaxHeight() || !Positions.isLoaded(world, target))
            return null;
        Location eye = player.getEyeLocation();
        Block block = Positions.block(world, target);
        BoundingBox box = block.getBoundingBox();
        Location aim = box.getVolume() > 1.0E-6
                ? new Location(world, box.getCenterX(), box.getCenterY(), box.getCenterZ())
                : new Location(world, target.x() + 0.5, target.y() + fallbackHeight, target.z() + 0.5);
        if (box.getVolume() <= 1.0E-6 && block.getBlockData() instanceof org.bukkit.block.data.FaceAttachable attachable) {
            switch (attachable.getAttachedFace()) {
                case FLOOR -> aim.setY(target.y() + 0.125);
                case CEILING -> aim.setY(target.y() + 0.875);
                case WALL -> {
                    if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                        BlockFace facing = dir.getFacing();
                        aim.setX(target.x() + 0.5 - facing.getModX() * 0.4);
                        aim.setZ(target.z() + 0.5 - facing.getModZ() * 0.4);
                    }
                }
            }
        }
        if (eye.distanceSquared(aim) > PrimitiveAdapter.BLOCK_REACH * PrimitiveAdapter.BLOCK_REACH) return null;
        int minX = Math.min(eye.getBlockX(), target.x()) >> 4;
        int maxX = Math.max(eye.getBlockX(), target.x()) >> 4;
        int minZ = Math.min(eye.getBlockZ(), target.z()) >> 4;
        int maxZ = Math.max(eye.getBlockZ(), target.z()) >> 4;
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                if (!world.isChunkLoaded(x, z)) return null;
        Vector dir = aim.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 1.0E-6) return BlockFace.UP;
        RayTraceResult hit = world.rayTraceBlocks(eye, dir.normalize(), dist + 0.5, FluidCollisionMode.NEVER, ignorePassable);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) return null;
        return Positions.of(hit.getHitBlock()).equals(target) ? hit.getHitBlockFace() : null;
    }

    public static String unavailableReason(AIPlayer ai, PrimitiveCommand.LootChest cmd) {
        Player player = ai.getPlayer();
        World world = player.getWorld();
        if (!world.getUID().equals(cmd.world())) return "chest world changed";
        BlockPoint chest = cmd.chest();
        if (chest.y() < world.getMinHeight() || chest.y() >= world.getMaxHeight() || !Positions.isLoaded(world, chest))
            return "chest unavailable";
        Block block = Positions.block(world, chest);
        if (!isLootableContainer(block.getType()) || !(block.getState(false) instanceof Container))
            return "target is not a chest or barrel";
        if (visibleHitFace(player, chest, 0.5, true) == null) return "chest outside reach or blocked from view";
        return "";
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        String reason = unavailableReason(ai, command);
        if (!reason.isEmpty()) {
            fail(reason);
            return;
        }
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, command.chest())) {
            fail("world changed");
            return;
        }
        if (!opened) {
            String reason = unavailableReason(ai, command);
            if (!reason.isEmpty()) {
                fail(reason);
                return;
            }
            BlockFace face = visibleHitFace(player, command.chest(), 0.5, true);
            if (face == null) {
                fail("chest outside reach or blocked from view");
                return;
            }
            Location center = Positions.center(world, command.chest());
            ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
            ai.getBody().inputSneak(false);
            ai.getBody().interactBlock(command.chest(), face);
            Inventory top = player.getOpenInventory().getTopInventory();
            if (!isOpenedChestInventory(top)) {
                fail("cannot open chest");
                return;
            }
            opened = true;
            openedAt = getElapsed();
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!isOpenedChestInventory(top)) {
            fail("chest closed unexpectedly");
            return;
        }
        if (getElapsed() - openedAt < OPEN_TICKS) return;
        int taken = takeAvailable(player, top);
        if (taken > 0) succeed();
        else fail("chest empty or inventory full");
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        if (opened) ai.getPlayer().closeInventory();
    }

    private static boolean isOpenedChestInventory(Inventory top) {
        if (top == null) return false;
        InventoryType type = top.getType();
        if (type != InventoryType.CHEST && type != InventoryType.BARREL) return false;
        return top.getHolder() instanceof Container || top.getHolder() instanceof DoubleChest;
    }

    private static int takeAvailable(Player player, Inventory chestInventory) {
        int taken = 0;
        for (int slot = 0; slot < chestInventory.getSize(); slot++) {
            ItemStack item = chestInventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            ItemStack leftover = player.getInventory().addItem(item.clone()).values().stream().findFirst().orElse(null);
            chestInventory.setItem(slot, leftover);
            if (leftover == null || leftover.getAmount() < item.getAmount()) taken++;
        }
        return taken;
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.INTERACT_BLOCK;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(command.chest());
    }
}
