package me.herry.minecraftAI.ai.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * BlockPoint 와 Bukkit 좌표 사이의 변환, 그리고 월드를 고려한 거리 계산.
 */
public final class Positions {
    private Positions() {
    }

    public static BlockPoint of(Location location) {
        return new BlockPoint(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * 플레이어가 "서 있는 칸". 반블록이나 흙길처럼 높이가 한 칸보다 낮은 블록 위에 서 있으면
     * 발 좌표가 그 블록 안으로 계산되므로, 반 칸을 올려서 그 위 칸으로 본다.
     */
    public static BlockPoint feet(Location location) {
        return new BlockPoint(location.getBlockX(), (int) Math.floor(location.getY() + 0.5), location.getBlockZ());
    }

    /**
     * 그 자리에 생성할 때 발을 둘 위치. 흙길, 경작지, 반블록처럼 한 칸보다 낮은 블록의 칸이면 그 블록의 윗면으로 올린다.
     * 그대로 두면 블록 안에 끼인 채로 생성되고, 아래 블록이 없어지면 그 블록을 통과해 떨어진다.
     * (진짜 플레이어는 서버가 접속할 때 겹치지 않는 높이로 올려 주지만, 가짜 플레이어는 그 과정을 거치지 않는다.)
     */
    public static Location standingSpot(Location location) {
        // 생성하면 어차피 그 청크가 로드되므로, 로드되지 않은 청크라도 여기서 블록을 읽는다.
        if (location.getWorld() == null) return location;
        Block block = location.getBlock();
        if (block.isPassable()) return location;
        Location spot = location.clone();
        spot.setY(block.getBoundingBox().getMaxY());
        return spot;
    }

    public static BlockPoint of(Block block) {
        return new BlockPoint(block.getX(), block.getY(), block.getZ());
    }

    public static Block block(World world, BlockPoint point) {
        return world.getBlockAt(point.x(), point.y(), point.z());
    }

    public static Location center(World world, BlockPoint point) {
        return new Location(world, point.x() + 0.5, point.y() + 0.5, point.z() + 0.5);
    }

    public static boolean isLoaded(World world, BlockPoint point) {
        return world.isChunkLoaded(point.x() >> 4, point.z() >> 4);
    }

    public static boolean sameWorld(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    // 월드가 다르면 Location#distance 가 예외를 던지므로 무한대로 취급한다.
    public static double distance(Location a, Location b) {
        if (!sameWorld(a, b)) return Double.POSITIVE_INFINITY;
        return a.distance(b);
    }

    public static double distanceSq(Location a, Location b) {
        if (!sameWorld(a, b)) return Double.POSITIVE_INFINITY;
        return a.distanceSquared(b);
    }

    // 마인크래프트 yaw: 0 = 남쪽(+Z), -90 = 동쪽(+X)
    public static float yawTo(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static float pitchTo(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    }

    public static float angleDifference(float from, float to) {
        float diff = (to - from) % 360.0F;
        if (diff >= 180.0F) diff -= 360.0F;
        if (diff < -180.0F) diff += 360.0F;
        return diff;
    }
}
