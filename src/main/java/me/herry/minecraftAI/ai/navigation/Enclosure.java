package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 지금 서 있는 빈 공간을 따라가 본다. 사방이 막힌 작은 공간(파고 들어가 막은 자리, 양쪽을 막은 굴 등) 안에 있으면
 * 바깥의 몬스터는 걸어 들어오지도, 보지도 못한다.
 * 한 칸 높이의 틈으로 이어진 곳도 같은 공간으로 친다. 지나다니지는 못해도 그 틈으로 보고 때릴 수 있기 때문이다.
 */
public final class Enclosure {
    // 여기까지만 따라가 본다. 동굴이나 지상처럼 트인 곳은 금방 이 수를 넘는다.
    private static final int MAX_CELLS = 96;
    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * @param cells  발과 머리 칸에서 이어진 빈칸. 가까운 칸부터 정해진 수까지만 담는다.
     * @param closed 이어진 빈칸을 전부 담았는지. 너무 넓거나 확인할 수 없는 청크에 닿으면 false 이고, 막힌 공간이 아니다.
     */
    public record Space(Set<BlockPoint> cells, boolean closed) {
    }

    private Enclosure() {
    }

    public static Space around(TerrainView terrain, BlockPoint feet) {
        Set<BlockPoint> cells = new HashSet<>();
        Deque<BlockPoint> queue = new ArrayDeque<>();
        for (BlockPoint start : List.of(feet, feet.offset(0, 1, 0))) {
            if (cells.add(start)) queue.add(start);
        }
        boolean closed = true;
        while (!queue.isEmpty()) {
            BlockPoint cell = queue.poll();
            for (int[] side : NEIGHBORS) {
                BlockPoint next = cell.offset(side[0], side[1], side[2]);
                BlockClass type = terrain.classify(next.x(), next.y(), next.z());
                if (type == BlockClass.SOLID || cells.contains(next)) continue;
                if (type == BlockClass.UNLOADED || cells.size() >= MAX_CELLS) {
                    closed = false;
                    continue;
                }
                cells.add(next);
                queue.add(next);
            }
        }
        return new Space(cells, closed);
    }

    /**
     * 그 블록들을 캐면 새로 이어지는 바깥의 빈칸들. 이미 내 공간인 칸이나 함께 캐는 칸은 바깥이 아니다.
     */
    public static List<BlockPoint> openings(TerrainView terrain, Set<BlockPoint> inside, Collection<BlockPoint> toBreak) {
        List<BlockPoint> outside = new ArrayList<>();
        for (BlockPoint block : toBreak) {
            if (terrain.classify(block.x(), block.y(), block.z()) != BlockClass.SOLID) continue;
            for (int[] side : NEIGHBORS) {
                BlockPoint next = block.offset(side[0], side[1], side[2]);
                if (inside.contains(next) || toBreak.contains(next)) continue;
                if (terrain.classify(next.x(), next.y(), next.z()) != BlockClass.SOLID) outside.add(next);
            }
        }
        return outside;
    }
}
