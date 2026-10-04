package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

/**
 * 어떤 (x, z) 위치에서 설 수 있는 높이를 찾는다. 도망갈 곳이나 탐험할 곳을 정할 때 쓴다.
 */
public final class GroundFinder {
    private GroundFinder() {
    }

    /**
     * aroundY 에서 가까운 높이부터 위아래로 번갈아 살펴서 설 수 있는 칸을 찾는다. 없으면 null.
     */
    public static @Nullable BlockPoint find(TerrainView terrain, int x, int aroundY, int z, int up, int down) {
        int limit = Math.max(up, down);
        for (int offset = 0; offset <= limit; offset++) {
            if (offset <= up && isSafe(terrain, x, aroundY + offset, z)) return new BlockPoint(x, aroundY + offset, z);
            if (offset > 0 && offset <= down && isSafe(terrain, x, aroundY - offset, z)) return new BlockPoint(x, aroundY - offset, z);
        }
        return null;
    }

    // 물속이 아닌 단단한 땅 위만 안전한 곳으로 본다.
    private static boolean isSafe(TerrainView terrain, int x, int y, int z) {
        return terrain.classify(x, y, z) == BlockClass.OPEN && AStarSearch.isStandable(terrain, x, y, z);
    }
}
