package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.plan.BucketRules;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 물 또는 용암 양동이를 블록의 윗면에 붓는다. 진짜 플레이어의 우클릭과 같은 서버 경로를 탄다.
 */
public final class PourBucketAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 60;
    private static final double MAX_REACH = 4.5;
    // 조금만 빗나가도 옆의 블록에 붓게 되므로 정확히 돌아선 뒤에 쓴다.
    private static final float FACING_TOLERANCE = 2.0F;
    private static final int MAX_AIM_TICKS = 20;

    private final BlockPoint support;
    private final BlockPoint cell;
    private final UUID requiredWorld;
    private final Material expectedBucket;
    private World world;

    /**
     * @param support 윗면에 물을 부을 블록. 물은 그 바로 위 칸에 생긴다
     */
    public PourBucketAction(BlockPoint support) {
        this(support, null, Material.WATER_BUCKET);
    }

    public PourBucketAction(BlockPoint support, UUID requiredWorld, Material expectedBucket) {
        super("PourBucket", TIMEOUT);
        this.support = support;
        this.cell = support.offset(0, 1, 0);
        this.requiredWorld = requiredWorld;
        this.expectedBucket = expectedBucket == Material.LAVA_BUCKET ? Material.LAVA_BUCKET : Material.WATER_BUCKET;
    }

    public static String heldBucketUnavailable(Player player, UUID requiredWorld, BlockPoint support, Material expectedBucket) {
        World world = player.getWorld();
        if (!world.getUID().equals(requiredWorld)) return "bucket pour world changed";
        if (expectedBucket != Material.WATER_BUCKET && expectedBucket != Material.LAVA_BUCKET) return "unsupported pour bucket";
        if (player.getInventory().getItemInMainHand().getType() != expectedBucket) return "no held matching bucket";
        if (expectedBucket == Material.WATER_BUCKET && BucketRules.evaporates(world.getEnvironment() == World.Environment.NETHER))
            return "would evaporate";
        BlockPoint cell = support.offset(0, 1, 0);
        if (support.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight()
                || !Positions.isLoaded(world, support) || !Positions.isLoaded(world, cell))
            return "pour target unavailable";
        if (!BucketRules.canPourInto(BukkitTerrainView.classify(Positions.block(world, cell).getType()),
                BukkitTerrainView.classify(Positions.block(world, support).getType())))
            return "no support";
        Location eye = player.getEyeLocation();
        Location top = new Location(world, support.x() + 0.5, support.y() + 1.0, support.z() + 0.5);
        if (eye.distanceSquared(top) > MAX_REACH * MAX_REACH || eye.getY() <= top.getY()) return "pour support outside reach";
        for (int x = Math.min(eye.getBlockX(), support.x()) >> 4; x <= Math.max(eye.getBlockX(), support.x()) >> 4; x++)
            for (int z = Math.min(eye.getBlockZ(), support.z()) >> 4; z <= Math.max(eye.getBlockZ(), support.z()) >> 4; z++)
                if (!world.isChunkLoaded(x, z)) return "pour sight path is unloaded";
        Vector dir = top.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 1.0E-6) return "not visible";
        RayTraceResult hit = world.rayTraceBlocks(eye, dir.normalize(), Math.min(MAX_REACH, dist + 0.05), FluidCollisionMode.NEVER, false);
        return hit != null && hit.getHitBlock() != null && Positions.of(hit.getHitBlock()).equals(support)
                && hit.getHitBlockFace() == BlockFace.UP ? "" : "not visible";
    }

    private Material expectedFluid() {
        return expectedBucket == Material.LAVA_BUCKET ? Material.LAVA : Material.WATER;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        if (requiredWorld != null) {
            String reason = heldBucketUnavailable(ai.getPlayer(), requiredWorld, support, expectedBucket);
            if (!reason.isEmpty()) { fail(reason); return; }
            ai.getNavigation().stop();
            ai.getBody().clearInputs();
        } else {
            if (expectedBucket == Material.WATER_BUCKET && BucketRules.evaporates(world.getEnvironment() == World.Environment.NETHER)) {
                fail("would evaporate");
                return;
            }
            if (!ai.getInventory().equip(expectedBucket)) {
                fail("no water bucket");
                return;
            }
        }
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, support)) {
            fail("world changed");
            return;
        }
        if (requiredWorld != null) {
            String reason = heldBucketUnavailable(player, requiredWorld, support, expectedBucket);
            if (!reason.isEmpty()) { fail(reason); return; }
        } else if (!BucketRules.canPourInto(BukkitTerrainView.classify(Positions.block(world, cell).getType()),
                BukkitTerrainView.classify(Positions.block(world, support).getType()))) {
            fail("no support");
            return;
        }
        double x = support.x() + 0.5;
        double y = support.y() + 1.0;
        double z = support.z() + 0.5;
        if (player.getEyeLocation().distance(new Location(world, x, y, z)) > MAX_REACH) {
            fail("out of reach");
            return;
        }

        ai.getBody().lookAt(x, y, z);
        if (!ai.getBody().isFacing(x, y, z, FACING_TOLERANCE)) {
            if (getElapsed() >= MAX_AIM_TICKS) fail("cannot aim");
            return;
        }
        if (player.getInventory().getItemInMainHand().getType() != expectedBucket) {
            fail("no matching bucket");
            return;
        }
        Location eye = player.getEyeLocation();
        RayTraceResult hit = world.rayTraceBlocks(eye, eye.getDirection(), MAX_REACH, FluidCollisionMode.NEVER, false);
        if (hit == null || hit.getHitBlock() == null || !Positions.of(hit.getHitBlock()).equals(support) || hit.getHitBlockFace() != BlockFace.UP) {
            fail("not visible");
            return;
        }

        ai.getBody().useItem();
        if (FillBucketAction.isSource(Positions.block(world, cell), expectedFluid())) {
            ai.debug("Poured " + expectedFluid().name() + " at " + cell);
            succeed();
        } else if (player.getInventory().getItemInMainHand().getType() == expectedBucket) {
            fail("use denied");
        } else {
            ai.debug("Poured " + expectedFluid().name() + ", but not at " + cell);
            fail("missed");
        }
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.USE_ITEM;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(cell);
    }
}
