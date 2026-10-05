package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 손이 닿는 범위에서 특정 블록(작업대, 화로 등)을 찾는다.
 */
public final class NearbyBlocks {
    private NearbyBlocks() {
    }

    // 눈에서 radius + 0.5 칸 안에 있고 손이 닿는 가장 가까운 블록. 없으면 null.
    static @Nullable BlockPoint find(Player player, Material material, int radius) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        int ex = eye.getBlockX();
        int ey = eye.getBlockY();
        int ez = eye.getBlockZ();

        BlockPoint best = null;
        double bestDistance = radius + 0.5;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    BlockPoint point = new BlockPoint(ex + dx, ey + dy, ez + dz);
                    if (!is(world, point, material)) continue;
                    double distance = Positions.center(world, point).distance(eye);
                    if (distance <= bestDistance && isUncovered(world, eye, point)) {
                        bestDistance = distance;
                        best = point;
                    }
                }
            }
        }
        return best;
    }

    /**
     * 눈에서 그 블록에 손이 닿는지. 거리 안에 있고, 사이를 꽉 찬 블록이 가로막고 있지 않아야 한다.
     * 실제 플레이어가 블록을 쓸 수 있는 조건과 같다. 벽 너머의 작업대나 화로는 가까워도 쓸 수 없다.
     */
    public static boolean canTouch(Player player, BlockPoint point, double reach) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        return Positions.center(world, point).distance(eye) <= reach && isUncovered(world, eye, point);
    }

    private static boolean isUncovered(World world, Location eye, BlockPoint point) {
        return Visibility.canSeeBlock(PerceptionSystem.opacityOf(world), eye.getX(), eye.getY(), eye.getZ(), point.x(), point.y(), point.z());
    }

    static boolean is(World world, @Nullable BlockPoint point, Material material) {
        if (point == null || point.y() < world.getMinHeight() || point.y() >= world.getMaxHeight()) return false;
        return Positions.isLoaded(world, point) && Positions.block(world, point).getType() == material;
    }
}
