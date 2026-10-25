package me.herry.minecraftAI.ai.farming;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Farmland;

/** Main-thread checks shared by observed candidates and the normal-input executor. */
public final class FarmChecks {
    public static final double REACH = 4.5;
    private FarmChecks() {}

    /** Known, loaded water is rechecked. Hidden neighboring blocks are not searched for water. */
    public static boolean waterObserved(AIPlayer ai, BlockPoint soil) {
        World world = ai.getPlayer().getWorld();
        var eye = ai.getPlayer().getEyeLocation();
        var opacity = PerceptionSystem.opacityOf(world);
        int examined = 0;
        for (var entry : ai.getMemory().all(MemoryType.WATER_SOURCE, world.getUID(), ai.getTicks())) {
            if (++examined > 32 || ai.getWorkBudget().isExhausted()) break;
            BlockPoint water = entry.pos();
            if (Math.abs(water.x() - soil.x()) > 4 || Math.abs(water.z() - soil.z()) > 4
                    || water.y() < soil.y() || water.y() > soil.y() + 1 || !Positions.isLoaded(world, water)) continue;
            if (Positions.block(world, water).getType() != Material.WATER) continue;
            if (Visibility.canSeeBlock(opacity, eye.getX(), eye.getY(), eye.getZ(), water.x(), water.y(), water.z())) return true;
        }
        return false;
    }

    public static boolean hydrated(World world, BlockPoint soil) {
        return Positions.block(world, soil).getBlockData() instanceof Farmland farm && farm.getMoisture() > 0;
    }

    public static boolean soilVisible(AIPlayer ai, BlockPoint soil) {
        var eye = ai.getPlayer().getEyeLocation();
        return Visibility.canSeeBlock(PerceptionSystem.opacityOf(ai.getPlayer().getWorld()),
                eye.getX(), eye.getY(), eye.getZ(), soil.x(), soil.y(), soil.z());
    }

    public static String unavailable(AIPlayer ai, World expectedWorld, BlockPoint soil) {
        if (!ai.getBody().isUsable()) return "body unavailable";
        if (!expectedWorld.equals(ai.getPlayer().getWorld())) return "world changed";
        if (soil.y() < expectedWorld.getMinHeight() || soil.y() + 1 >= expectedWorld.getMaxHeight()
                || !Positions.isLoaded(expectedWorld, soil) || !Positions.isLoaded(expectedWorld, soil.offset(0, 1, 0))) return "plot unavailable";
        if (ai.getPlayer().isInWater() || !ai.getBody().isGrounded()) return "stable dry footing required";
        if (ai.getTeam().isClaimedByOther(ai, expectedWorld.getUID(), soil)) return "plot claimed";
        if (ai.getMemory().contains(MemoryType.UNREACHABLE, expectedWorld.getUID(), soil, ai.getTicks())) return "plot temporarily unreachable";
        BlockPoint feet = ai.getPosition();
        if (feet.x() == soil.x() && feet.z() == soil.z() && Math.abs(feet.y() - soil.y()) <= 1) return "standing on target plot";
        if (!Positions.block(expectedWorld, soil.offset(0, 1, 0)).getType().isAir()) return "plot occupied";
        if (Positions.block(expectedWorld, soil.offset(0, 1, 0)).getLightLevel() < 9) return "insufficient observed light";
        if (!waterObserved(ai, soil)) return "nearby irrigation not observed";
        Location point = top(expectedWorld, soil);
        if (ai.getPlayer().getEyeLocation().distance(point) > REACH) return "out of reach";
        return "";
    }

    public static Location top(World world, BlockPoint soil) {
        double y = Positions.block(world, soil).getBoundingBox().getMaxY();
        return new Location(world, soil.x() + 0.5, y, soil.z() + 0.5);
    }

    public static boolean visibleTop(AIPlayer ai, BlockPoint soil) {
        var player = ai.getPlayer();
        Location eye = player.getEyeLocation();
        var direction = top(player.getWorld(), soil).toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (distance < 1.0E-6 || distance > REACH) return false;
        var hit = player.getWorld().rayTraceBlocks(eye, direction.normalize(), distance + 0.05, FluidCollisionMode.NEVER, true);
        return hit != null && hit.getHitBlock() != null && Positions.of(hit.getHitBlock()).equals(soil)
                && hit.getHitBlockFace() == BlockFace.UP;
    }

    public static void stop(AIPlayer ai) {
        ai.getNavigation().stop();
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
        ai.getBody().inputJump(false);
        ai.getBody().inputSneak(false);
    }
}
