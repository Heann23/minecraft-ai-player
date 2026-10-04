package me.herry.minecraftAI.ai.action;

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
final class NearbyBlocks {
    private NearbyBlocks() {
    }

    // 눈에서 radius + 0.5 칸 안에 있는 가장 가까운 블록. 없으면 null.
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
                    if (distance <= bestDistance) {
                        bestDistance = distance;
                        best = point;
                    }
                }
            }
        }
        return best;
    }

    static boolean is(World world, @Nullable BlockPoint point, Material material) {
        if (point == null || point.y() < world.getMinHeight() || point.y() >= world.getMaxHeight()) return false;
        return Positions.isLoaded(world, point) && Positions.block(world, point).getType() == material;
    }
}
