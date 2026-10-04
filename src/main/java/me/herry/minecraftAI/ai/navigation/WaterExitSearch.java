package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** 물에서 나올 실제 경로를 찾고, 없으면 바로 옆 둑에 한 칸짜리 출구를 낸다. */
public final class WaterExitSearch {
    private static final int RADIUS = 6;
    private static final int MAX_CANDIDATES = 24;
    private static final int SEARCH_NODES = 128;
    private static final int[][] SIDES = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
    private static final int[][] NEIGHBORS = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

    public record Exit(BlockPoint feet, List<BlockPoint> blocksToBreak) {}

    private WaterExitSearch() {}

    public static @Nullable Exit find(TerrainView terrain, BlockPoint start, Predicate<BlockPoint> canBreak) {
        List<BlockPoint> shores = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -3; dy <= 4; dy++) {
                    BlockPoint feet = start.offset(dx, dy, dz);
                    if (isDrySafe(terrain, feet)) shores.add(feet);
                }
            }
        }
        shores.sort(Comparator.comparingDouble(point -> distance(start, point)));
        for (int i = 0; i < Math.min(MAX_CANDIDATES, shores.size()); i++) {
            BlockPoint feet = shores.get(i);
            if (reachable(terrain, start, feet)) return new Exit(feet, List.of());
        }

        // 수면 바로 위 칸만 판다. 물 아래 벽을 허물어 출구가 다시 물에 잠기는 것을 피한다.
        if (terrain.classify(start.x(), start.y() + 1, start.z()) != BlockClass.OPEN) return null;
        for (int[] side : SIDES) {
            BlockPoint feet = start.offset(side[0], 1, side[1]);
            if (terrain.classify(feet.x(), feet.y() - 1, feet.z()) != BlockClass.SOLID) continue;
            List<BlockPoint> cuts = new ArrayList<>();
            boolean safe = true;
            // 출발 칸의 낮은 천장도 치워야 한 칸 높은 둑으로 올라갈 수 있다.
            for (BlockPoint block : List.of(start.offset(0, 2, 0), feet.offset(0, 1, 0), feet)) {
                BlockClass type = terrain.classify(block.x(), block.y(), block.z());
                if (type == BlockClass.OPEN) continue;
                if (type != BlockClass.SOLID || !canBreak.test(block) || touchesUnsafe(terrain, block)) {
                    safe = false;
                    break;
                }
                cuts.add(block);
            }
            if (!safe || cuts.isEmpty()) continue;
            TerrainView opened = (x, y, z) -> cuts.contains(new BlockPoint(x, y, z))
                    ? BlockClass.OPEN : terrain.classify(x, y, z);
            if (isDrySafe(opened, feet) && reachable(opened, start, feet)) {
                return new Exit(feet, List.copyOf(cuts));
            }
        }
        return null;
    }

    private static boolean reachable(TerrainView terrain, BlockPoint start, BlockPoint feet) {
        AStarSearch search = new AStarSearch(terrain, start, PathGoal.arrive(feet, 0.3), SEARCH_NODES, RADIUS, 3);
        return search.advance(SEARCH_NODES) == AStarSearch.State.FOUND;
    }

    private static boolean isDrySafe(TerrainView terrain, BlockPoint feet) {
        if (terrain.classify(feet.x(), feet.y(), feet.z()) != BlockClass.OPEN
                || !AStarSearch.isStandable(terrain, feet.x(), feet.y(), feet.z())) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (terrain.classify(feet.x() + dx, feet.y(), feet.z() + dz) == BlockClass.DANGER
                        || terrain.classify(feet.x() + dx, feet.y() - 1, feet.z() + dz) == BlockClass.DANGER) return false;
            }
        }
        return true;
    }

    // 캐면 물이나 용암이 흘러들거나, 확인할 수 없는 청크에 닿는 블록인지.
    static boolean touchesUnsafe(TerrainView terrain, BlockPoint block) {
        for (int[] side : NEIGHBORS) {
            BlockClass type = terrain.classify(block.x() + side[0], block.y() + side[1], block.z() + side[2]);
            if (type == BlockClass.WATER || type == BlockClass.DANGER || type == BlockClass.UNLOADED) return true;
        }
        return false;
    }

    private static double distance(BlockPoint from, BlockPoint to) {
        int dx = from.x() - to.x(), dy = from.y() - to.y(), dz = from.z() - to.z();
        return dx * dx + dz * dz + 2.0 * dy * dy;
    }
}
