package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;

/**
 * 달리면서 점프를 섞어도 되는 곧은 평지 구간인지 판단한다.
 * 달리며 점프하면 한 번에 네 칸 가까이 날아가므로, 떨어질 자리가 모두 같은 높이의 안전한 땅일 때만 뛴다.
 * Bukkit 에 의존하지 않는다.
 */
final class RunAhead {
    // 점프 한 번으로 날아가는 거리에 여유를 둔 칸 수. 이만큼이 한 줄로 이어져 있어야 뛴다.
    static final int LENGTH = 5;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private RunAhead() {
    }

    /**
     * 경로의 index 번째 칸부터 LENGTH 칸이, 직전 칸에서 이어지는 방향 그대로 같은 높이에 한 줄로 이어져 있고 뛰어도 안전한지.
     * 경로가 그만큼 남지 않았거나, 출발한 직후라 직전 칸이 없으면 false.
     */
    static boolean isClear(TerrainView terrain, Path path, int index) {
        if (index < 1 || index + LENGTH > path.size()) return false;
        BlockPoint from = path.get(index - 1);
        BlockPoint first = path.get(index);
        int dx = first.x() - from.x();
        int dz = first.z() - from.z();
        if (first.y() != from.y() || Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) return false;

        for (int i = 0; i < LENGTH; i++) {
            BlockPoint cell = path.get(index + i);
            BlockPoint previous = path.get(index + i - 1);
            if (cell.y() != from.y() || cell.x() - previous.x() != dx || cell.z() - previous.z() != dz) return false;
            if (!isSafeToLand(terrain, cell)) return false;
        }
        return true;
    }

    // 그 칸 위로 뛰어도 머리가 닿지 않고, 조금 빗나가 옆 칸에 내려도 떨어지거나 빠지지 않는지.
    private static boolean isSafeToLand(TerrainView terrain, BlockPoint cell) {
        int x = cell.x();
        int y = cell.y();
        int z = cell.z();
        if (!isFlatGround(terrain, x, y, z) || terrain.classify(x, y + 2, z) != BlockClass.OPEN) return false;
        for (int[] side : SIDES) {
            int sx = x + side[0];
            int sz = z + side[1];
            // 옆이 벽이면 그쪽으로는 빗나갈 수 없다.
            boolean wall = terrain.classify(sx, y, sz) == BlockClass.SOLID;
            if (!wall && !isFlatGround(terrain, sx, y, sz)) return false;
        }
        return true;
    }

    // 발과 머리 칸이 비어 있고 바로 아래가 단단한 땅인지 (물, 거미줄, 낭떠러지, 위험한 블록이 아닌지)
    private static boolean isFlatGround(TerrainView terrain, int x, int y, int z) {
        return terrain.classify(x, y, z) == BlockClass.OPEN && terrain.classify(x, y + 1, z) == BlockClass.OPEN
                && terrain.classify(x, y - 1, z) == BlockClass.SOLID;
    }
}
