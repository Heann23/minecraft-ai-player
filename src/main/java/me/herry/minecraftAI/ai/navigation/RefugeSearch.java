package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 몬스터에게 몰렸을 때 숨을 자리를 찾는다. 사방과 위가 막힌 한 칸(높이 2칸) 안에 들어가 있으면
 * 좀비는 들어오지 못하고, 보이지 않으므로 스켈레톤은 쏘지 못하고 크리퍼는 터지지 않는다.
 * 지금 선 자리를 막거나, 옆 벽을 한 칸 파고 들어가서 들어온 쪽을 막거나, 발밑을 세 칸 파고 들어가서 위를 덮는다.
 * 좌표와 지형 분류만 보고 판단해서 서버 없이 테스트할 수 있다.
 */
public final class RefugeSearch {
    // 이보다 많이 막아야 하는 자리는 다 막기 전에 몬스터가 들어온다.
    private static final int MAX_SEAL = 6;
    private static final double BREAK_COST = 3.0;
    private static final double SEAL_COST = 2.0;
    // 몬스터가 오는 쪽의 벽으로는 되도록 파고들지 않는다.
    private static final double TOWARD_THREAT_COST = 4.0;
    private static final int HOLE_DEPTH = 3;
    private static final int[][] SIDES = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

    /**
     * @param cell    들어가서 설 칸 (발)
     * @param toBreak 들어가려고 캐야 하는 블록. 캐는 순서대로다.
     * @param toSeal  들어간 뒤 블록을 놓아 막아야 하는 칸. 놓는 순서대로다 (윗칸은 아랫칸을 딛고 놓는다).
     */
    public record Refuge(BlockPoint cell, List<BlockPoint> toBreak, List<BlockPoint> toSeal) {
    }

    private RefugeSearch() {
    }

    /**
     * @param threat          가장 가까운 몬스터의 위치. 모르면 null.
     * @param canBreak        캐도 되는 블록인지 (집, 지나온 굴의 발판, 캘 수 없는 블록은 제외)
     * @param blocksAvailable 막는 데 쓸 수 있는 블록의 개수
     */
    public static @Nullable Refuge find(TerrainView terrain, BlockPoint feet, @Nullable BlockPoint threat,
                                        Predicate<BlockPoint> canBreak, int blocksAvailable) {
        List<Refuge> candidates = new ArrayList<>();
        addIfUsable(candidates, terrain, feet, List.of(), threat, blocksAvailable);
        for (int[] side : SIDES) {
            BlockPoint cell = feet.offset(side[0], 0, side[1]);
            if (terrain.classify(cell.x(), cell.y() - 1, cell.z()) != BlockClass.SOLID) continue;
            List<BlockPoint> dig = diggable(terrain, canBreak, cell.offset(0, 1, 0), cell);
            if (dig != null) addIfUsable(candidates, terrain, cell, dig, threat, blocksAvailable);
        }
        // 옆에 벽이 없는 넓은 곳에서는 발밑을 판다. 맨 위 칸은 구멍의 가장자리에 붙여서 덮을 자리다.
        BlockPoint bottom = feet.offset(0, -HOLE_DEPTH, 0);
        if (terrain.classify(bottom.x(), bottom.y() - 1, bottom.z()) == BlockClass.SOLID) {
            List<BlockPoint> dig = diggable(terrain, canBreak, feet.offset(0, -1, 0), feet.offset(0, -2, 0), bottom);
            if (dig != null && dig.size() == HOLE_DEPTH) addIfUsable(candidates, terrain, bottom, dig, threat, blocksAvailable);
        }

        Refuge best = null;
        double bestCost = Double.MAX_VALUE;
        for (Refuge refuge : candidates) {
            double cost = refuge.toBreak().size() * BREAK_COST + refuge.toSeal().size() * SEAL_COST;
            if (threat != null && isToward(feet, refuge.cell(), threat)) cost += TOWARD_THREAT_COST;
            if (cost < bestCost) {
                bestCost = cost;
                best = refuge;
            }
        }
        return best;
    }

    // 캐야 하는 블록의 목록. 이미 비어 있는 칸은 빼고, 캘 수 없거나 캐면 위험한 블록이 하나라도 있으면 null.
    private static @Nullable List<BlockPoint> diggable(TerrainView terrain, Predicate<BlockPoint> canBreak, BlockPoint... blocks) {
        List<BlockPoint> dig = new ArrayList<>();
        for (BlockPoint block : blocks) {
            BlockClass type = terrain.classify(block.x(), block.y(), block.z());
            if (type == BlockClass.OPEN) continue;
            if (type != BlockClass.SOLID || !canBreak.test(block) || WaterExitSearch.touchesUnsafe(terrain, block)) return null;
            dig.add(block);
        }
        return dig;
    }

    private static void addIfUsable(List<Refuge> candidates, TerrainView terrain, BlockPoint cell, List<BlockPoint> toBreak,
                                    @Nullable BlockPoint threat, int blocksAvailable) {
        TerrainView opened = (x, y, z) -> toBreak.contains(new BlockPoint(x, y, z)) ? BlockClass.OPEN : terrain.classify(x, y, z);
        List<BlockPoint> toSeal = new ArrayList<>();
        for (int level = 0; level <= 1; level++) {
            List<BlockPoint> openings = new ArrayList<>();
            for (int[] side : SIDES) {
                BlockPoint neighbor = cell.offset(side[0], level, side[1]);
                BlockClass type = opened.classify(neighbor.x(), neighbor.y(), neighbor.z());
                if (type == BlockClass.SOLID) continue;
                // 물이나 용암이 닿는 자리는 막을 수 없고, 아랫칸은 바닥이 있어야 블록을 놓을 수 있다.
                if (type != BlockClass.OPEN) return;
                if (level == 0 && terrain.classify(neighbor.x(), neighbor.y() - 1, neighbor.z()) != BlockClass.SOLID) return;
                openings.add(neighbor);
            }
            // 몬스터가 오는 쪽부터 막는다.
            if (threat != null) openings.sort(Comparator.comparingDouble(point -> point.distanceSq(threat)));
            toSeal.addAll(openings);
        }
        BlockPoint top = cell.offset(0, 2, 0);
        BlockClass topType = opened.classify(top.x(), top.y(), top.z());
        if (topType == BlockClass.OPEN) {
            if (!hasSolidSide(terrain, top)) return;
            toSeal.add(top);
        } else if (topType != BlockClass.SOLID) {
            return;
        }
        if (toSeal.size() > MAX_SEAL || toSeal.size() > blocksAvailable) return;
        candidates.add(new Refuge(cell, List.copyOf(toBreak), List.copyOf(toSeal)));
    }

    // 블록은 옆의 블록에 붙여서 놓는다. 허공에 뜬 칸에는 놓을 수 없다.
    private static boolean hasSolidSide(TerrainView terrain, BlockPoint cell) {
        for (int[] side : SIDES) {
            if (terrain.classify(cell.x() + side[0], cell.y(), cell.z() + side[1]) == BlockClass.SOLID) return true;
        }
        return false;
    }

    private static boolean isToward(BlockPoint feet, BlockPoint cell, BlockPoint threat) {
        int dx = cell.x() - feet.x();
        int dz = cell.z() - feet.z();
        return dx * (threat.x() - feet.x()) + dz * (threat.z() - feet.z()) > 0;
    }
}
