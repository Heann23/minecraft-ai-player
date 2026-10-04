package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;

/** 가까이 있는 굴도 단차나 벽으로 끊겨 있을 수 있으므로 첫 지점까지의 완전한 경로를 확인한다. */
public final class ShaftAccess {
    private static final int MAX_NODES = 512;
    private static final int MAX_DISTANCE = 20;

    private ShaftAccess() {}

    public static boolean canReach(TerrainView terrain, BlockPoint feet, PathGoal firstWaypoint) {
        AStarSearch search = new AStarSearch(terrain, feet, firstWaypoint, MAX_NODES, MAX_DISTANCE, 3);
        return search.advance(MAX_NODES) == AStarSearch.State.FOUND;
    }
}
