package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryType;
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
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 빈 양동이로 원천 물 또는 용암을 뜬다. 진짜 플레이어가 우클릭했을 때와 같은 경로라서 거리, 가로막은 블록, 보호 플러그인의 판정을 그대로 받는다.
 */
public final class FillBucketAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 60;
    private static final double MAX_REACH = 4.5;
    // 서버는 시선이 실제로 닿는 블록을 본다. 조금만 빗나가도 옆의 블록을 누르게 되므로 정확히 돌아선 뒤에 쓴다.
    private static final float FACING_TOLERANCE = 2.0F;
    private static final int MAX_AIM_TICKS = 20;
    // 원천 유체의 수면은 블록의 윗면보다 조금 낮다. 그 바로 아래를 겨눈다.
    private static final double AIM_HEIGHT = 0.8;

    private final BlockPoint source;
    private final UUID requiredWorld;
    private final Material expectedFluid;
    private World world;
    private int carried;

    public FillBucketAction(BlockPoint source) {
        this(source, null, Material.WATER);
    }

    public FillBucketAction(BlockPoint source, UUID requiredWorld, Material expectedFluid) {
        super("FillBucket", TIMEOUT);
        this.source = source;
        this.requiredWorld = requiredWorld;
        this.expectedFluid = expectedFluid == Material.LAVA ? Material.LAVA : Material.WATER;
    }

    public static String heldBucketUnavailable(Player player, UUID requiredWorld, BlockPoint source, Material expectedFluid) {
        World world = player.getWorld();
        if (!world.getUID().equals(requiredWorld)) return "bucket source world changed";
        if (player.getInventory().getItemInMainHand().getType() != Material.BUCKET) return "no held empty bucket";
        if (expectedFluid != Material.WATER && expectedFluid != Material.LAVA) return "unsupported bucket fluid";
        if (source.y() < world.getMinHeight() || source.y() >= world.getMaxHeight() || !Positions.isLoaded(world, source))
            return "fluid source unavailable";
        if (!isSource(Positions.block(world, source), expectedFluid)) return "not a matching fluid source";
        Location eye = player.getEyeLocation();
        Location aim = new Location(world, source.x() + 0.5, source.y() + AIM_HEIGHT, source.z() + 0.5);
        if (eye.distanceSquared(aim) > MAX_REACH * MAX_REACH) return "fluid source outside reach";
        for (int x = Math.min(eye.getBlockX(), source.x()) >> 4; x <= Math.max(eye.getBlockX(), source.x()) >> 4; x++)
            for (int z = Math.min(eye.getBlockZ(), source.z()) >> 4; z <= Math.max(eye.getBlockZ(), source.z()) >> 4; z++)
                if (!world.isChunkLoaded(x, z)) return "fluid sight path is unloaded";
        Vector dir = aim.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 1.0E-6) return "";
        RayTraceResult hit = world.rayTraceBlocks(eye, dir.normalize(), dist + 0.05, FluidCollisionMode.SOURCE_ONLY, true);
        return hit != null && hit.getHitBlock() != null && Positions.of(hit.getHitBlock()).equals(source)
                ? "" : "fluid source is not visible";
    }

    private Material targetBucket() {
        return expectedFluid == Material.LAVA ? Material.LAVA_BUCKET : Material.WATER_BUCKET;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        carried = ai.getInventory().count(targetBucket());
        if (requiredWorld != null) {
            String reason = heldBucketUnavailable(ai.getPlayer(), requiredWorld, source, expectedFluid);
            if (!reason.isEmpty()) { fail(reason); return; }
            ai.getNavigation().stop();
            ai.getBody().clearInputs();
        } else if (!ai.getInventory().equip(Material.BUCKET)) {
            fail("no bucket");
            return;
        }
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
        if (requiredWorld != null) {
            String reason = heldBucketUnavailable(player, requiredWorld, source, expectedFluid);
            if (!reason.isEmpty()) { fail(reason); return; }
        } else if (!isSource(Positions.block(world, source), expectedFluid)) {
            ai.getMemory().forget(MemoryType.WATER_SOURCE, world.getUID(), source);
            fail("not a source");
            return;
        }
        double x = source.x() + 0.5;
        double y = source.y() + AIM_HEIGHT;
        double z = source.z() + 0.5;
        if (player.getEyeLocation().distance(new Location(world, x, y, z)) > MAX_REACH) {
            fail("out of reach");
            return;
        }

        ai.getBody().lookAt(x, y, z);
        if (!ai.getBody().isFacing(x, y, z, FACING_TOLERANCE)) {
            if (getElapsed() >= MAX_AIM_TICKS) fail("cannot aim");
            return;
        }
        if (player.getInventory().getItemInMainHand().getType() != Material.BUCKET) {
            fail("no bucket");
            return;
        }

        ai.getBody().useItem();
        if (ai.getInventory().count(targetBucket()) > carried) {
            ai.debug("Filled a bucket with " + expectedFluid.name() + " at " + source);
            succeed();
            return;
        }
        if (requiredWorld == null) ai.getMemory().forget(MemoryType.WATER_SOURCE, world.getUID(), source);
        fail("use denied");
    }

    public static boolean isSource(Block block) {
        return isSource(block, Material.WATER);
    }

    public static boolean isSource(Block block, Material fluid) {
        return block.getType() == fluid && block.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0;
    }

    public static boolean isFillableSource(Block block) {
        return isSource(block, Material.WATER) || isSource(block, Material.LAVA);
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.USE_ITEM;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(source);
    }
}
