package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;

/**
 * 경로의 도착 조건.
 *
 * @param fromEye true 면 눈높이에서 대상 블록 중심까지의 거리로 판정한다 (블록을 캐거나 설치할 때 손이 닿는지).
 *                false 면 발 위치에서 대상 좌표까지의 거리로 판정한다.
 */
public record PathGoal(BlockPoint target, double radius, boolean fromEye) {
    private static final double EYE_HEIGHT = 1.62;

    public static PathGoal arrive(BlockPoint target, double radius) {
        return new PathGoal(target, radius, false);
    }

    public static PathGoal reach(BlockPoint target, double radius) {
        return new PathGoal(target, radius, true);
    }

    public double distance(int x, int y, int z) {
        double dx = x - target.x();
        double dz = z - target.z();
        double dy = fromEye ? (y + EYE_HEIGHT) - (target.y() + 0.5) : y - target.y();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public boolean reached(int x, int y, int z) {
        return distance(x, y, z) <= radius;
    }

    public double heuristic(int x, int y, int z) {
        return Math.max(0.0, distance(x, y, z) - radius);
    }
}
