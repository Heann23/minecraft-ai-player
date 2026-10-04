package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 가까이 있는 굴도 단차나 벽으로 끊겨 있을 수 있으므로 첫 지점까지의 완전한 경로를 확인한다. */
public final class ShaftAccess {
    private static final int MAX_NODES = 512;
    private static final int MAX_DISTANCE = 20;
    // 굴의 끝에서 이 거리(제곱, 8칸) 안에서 다시 파기 시작하면 그 사이를 걸어온 길도 굴에 이어 붙인다.
    private static final double LINK_DISTANCE_SQ = 64.0;
    private static final int LINK_NODES = 256;
    private static final int LINK_DISTANCE = 10;

    private ShaftAccess() {}

    public static boolean canReach(TerrainView terrain, BlockPoint feet, PathGoal firstWaypoint) {
        AStarSearch search = new AStarSearch(terrain, feet, firstWaypoint, MAX_NODES, MAX_DISTANCE, 3);
        return search.advance(MAX_NODES) == AStarSearch.State.FOUND;
    }

    /**
     * 굴의 끝(tail)과 다시 파기 시작하는 자리(point) 사이에 굴로 기록되지 않은 길이 끼어 있는지.
     * 굴 끝에서 몇 칸 걸어간 자리에서 새 계단을 파면, 그 사이의 칸은 걸어 다니는 길인데도 굴의 일부로 남지 않는다.
     * 바로 다음 단(옆으로 한 칸, 위아래 한 칸까지)이면 사이에 낀 칸이 없다.
     */
    public static boolean leavesGap(BlockPoint tail, BlockPoint point) {
        if (tail.equals(point) || tail.distanceSq(point) > LINK_DISTANCE_SQ) return false;
        int flat = Math.abs(tail.x() - point.x()) + Math.abs(tail.z() - point.z());
        return flat > 1 || Math.abs(tail.y() - point.y()) > 1;
    }

    /**
     * 굴의 끝에서 to 까지 걸어가는 길의 칸들 (양 끝은 뺀다). 사이에 낀 길이 없거나 걸어갈 수 없으면 빈 목록이다.
     * 이 칸들의 바닥을 파내면 굴과 새로 팔 자리 사이가 끊기므로, 파지 않고 굴에 이어서 기록한다.
     *
     * @param tail 가장 최근에 판 굴의 끝. 굴이 없으면 null.
     */
    public static List<BlockPoint> link(TerrainView terrain, @Nullable BlockPoint tail, BlockPoint to) {
        if (tail == null || !leavesGap(tail, to)) return List.of();
        AStarSearch search = new AStarSearch(terrain, tail, PathGoal.arrive(to, 0.0), LINK_NODES, LINK_DISTANCE, 3);
        if (search.advance(LINK_NODES) != AStarSearch.State.FOUND) return List.of();
        List<BlockPoint> cells = new ArrayList<>(search.getPath().points());
        cells.remove(tail);
        cells.remove(to);
        return cells;
    }
}
