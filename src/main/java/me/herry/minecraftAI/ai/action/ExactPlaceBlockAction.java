package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Objects;

/** Places one chosen solid block against a visible adjacent face with normal survival consumption. */
public final class ExactPlaceBlockAction extends AbstractAction implements PrimitiveAction {
    private static final double MAX_REACH = 4.9;
    private static final BlockFace[] FACES = {
            BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP
    };
    private final BlockPoint target;
    private final Material material;
    private World world;

    public ExactPlaceBlockAction(BlockPoint target, Material material) {
        super("PlaceExactBlock", 60);
        this.target = Objects.requireNonNull(target, "target");
        this.material = Objects.requireNonNull(material, "material");
    }

    /** Empty means available; both the action mask and executor use this same bounded check. */
    public static String unavailableReason(AIPlayer ai, BlockPoint target, Material material) {
        if (!ai.getBody().isUsable()) return "body unavailable";
        if (!material.isBlock() || !material.isItem() || !material.isOccluding() || material.hasGravity()) {
            return "only solid single-cell blocks supported";
        }
        World world = ai.getPlayer().getWorld();
        if (target.y() <= world.getMinHeight() || target.y() + 1 >= world.getMaxHeight()
                || !Positions.isLoaded(world, target)) return "target unavailable";
        // BlockPlacing reads neighboring support cells; never cause a synchronous chunk load.
        for (BlockFace face : FACES) {
            if (!Positions.isLoaded(world, target.offset(face.getModX(), face.getModY(), face.getModZ()))) {
                return "neighbor chunk not loaded";
            }
        }
        Block block = Positions.block(world, target);
        if (!block.getType().isAir()) return "target occupied";
        if (!block.canPlace(material.createBlockData()) || !BlockPlacing.canPlaceAt(block)) return "cannot place there";
        if (ai.getPlayer().getEyeLocation().distance(Positions.center(world, target)) > MAX_REACH) return "out of reach";
        if (!ai.getInventory().has(material)) return "item not in inventory";
        return visibleSupport(ai.getPlayer(), block) == null ? "support face not visible" : "";
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        String reason = unavailableReason(ai, target, material);
        if (!reason.isEmpty()) {
            fail(reason);
            return;
        }
        if (!ai.getInventory().equip(material)) {
            fail("item not in inventory");
            return;
        }
        ai.getNavigation().stop();
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
        ai.getBody().inputJump(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        if (!ai.getPlayer().getWorld().equals(world)) {
            fail("world changed");
            return;
        }
        String reason = unavailableReason(ai, target, material);
        if (!reason.isEmpty()) {
            fail(reason);
            return;
        }
        Player player = ai.getPlayer();
        Block block = Positions.block(world, target);
        Block support = visibleSupport(player, block);
        BlockFace face = block.getFace(support);
        Location point = Positions.center(world, target).add(face.getModX() * 0.5, face.getModY() * 0.5, face.getModZ() * 0.5);
        ai.getBody().lookAt(point.getX(), point.getY(), point.getZ());
        if (getElapsed() < 4 || !ai.getBody().isFacing(point.getX(), point.getY(), point.getZ(), 20.0F)) return;
        if (player.getInventory().getItemInMainHand().getType() != material) {
            fail("held item changed");
            return;
        }
        if (!BlockPlacing.place(player, block, support)) {
            fail("place denied");
            return;
        }
        succeed();
    }

    private static Block visibleSupport(Player player, Block block) {
        Location eye = player.getEyeLocation();
        for (BlockFace face : FACES) {
            Block support = block.getRelative(face);
            if (!support.getType().isSolid()) continue;
            Vector point = block.getLocation().add(0.5 + face.getModX() * 0.501,
                    0.5 + face.getModY() * 0.501, 0.5 + face.getModZ() * 0.501).toVector();
            Vector direction = point.subtract(eye.toVector());
            double distance = direction.length();
            if (distance < 1.0E-6 || distance > MAX_REACH) continue;
            RayTraceResult hit = block.getWorld().rayTraceBlocks(eye, direction.normalize(), distance + 0.01,
                    FluidCollisionMode.NEVER, true);
            if (hit != null && support.equals(hit.getHitBlock()) && hit.getHitBlockFace() == face.getOppositeFace()) {
                return support;
            }
        }
        return null;
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputJump(false);
        ai.getBody().inputSprint(false);
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.PLACE_BLOCK;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(target);
    }
}
