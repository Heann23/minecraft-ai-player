package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 용암 호수의 가장자리에서 흑요석을 만들 자리. 좌표와 지형 분류만 보는 순수 규칙이라 서버 없이 테스트한다.
 *
 * 옆에서 본 모습 (L 용암, S 둑, P 받침 블록, W 물 원천, @ AI):
 *
 *        @
 *        P  W
 *     S  S  S  L  L  L        stand 는 P 아래의 둑 블록, pour 는 W 아래의 둑 블록이다.
 *     S  S  S  L  L  L        W 는 그림에서 P 의 옆(안쪽이나 바깥쪽)에 있고, 둘 다 호수와 맞닿아 있다.
 *
 * 받침 위에 서 있으면 퍼지는 물이 발에 닿지 않아서 호수 쪽으로 떠밀리지 않는다.
 *
 * @param stand 받침을 놓고 올라설 둑 블록 (용암 표면과 같은 높이)
 * @param pour  그 윗면에 물을 부을 둑 블록. stand 의 옆이고 호수와 맞닿아 있다
 * @param lakeX 호수가 있는 쪽 (stand 에서 본 방향)
 * @param lakeZ 호수가 있는 쪽
 * @param cells 받침 위에서 손이 닿는 호수 표면의 칸 수. 많을수록 좋은 자리다
 */
public record ObsidianSite(BlockPoint stand, BlockPoint pour, int lakeX, int lakeZ, int cells) {
    // 받침 위에 선 눈높이에서 손이 닿는 거리. 서버가 허용하는 4.5칸보다 조금 짧게 잡는다.
    public static final double REACH = 4.4;
    // 둑 블록의 바닥에서 눈까지: 둑 한 칸 + 받침 한 칸 + 눈높이
    private static final double EYE_ABOVE_STAND = 1.0 + 1.0 + 1.62;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int WINDOW = 4;

    /**
     * 용암 원천 하나(lava)의 주변에서 가장 좋은 자리를 찾는다. 없으면 null.
     *
     * @param isLake 그 칸이 호수의 표면인지: 흐르지 않는 용암이거나, 그것이 이미 굳은 흑요석. 흐르는 용암에 물이 닿으면 조약돌이 된다
     * @param skip         쓰지 않을 둑 블록 (가 봤지만 안 됐던 자리)
     */
    public static @Nullable ObsidianSite find(TerrainView terrain, Predicate<BlockPoint> isLake, BlockPoint lava, int radius,
                                              Predicate<BlockPoint> skip) {
        ObsidianSite best = null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPoint stand = new BlockPoint(lava.x() + dx, lava.y(), lava.z() + dz);
                if (skip.test(stand)) continue;
                ObsidianSite site = at(terrain, isLake, stand, false);
                if (site == null) continue;
                // 손이 닿는 용암이 많은 자리가 먼저다. 같으면 좌표 순서로 골라서, 다시 물어도 같은 자리가 나오게 한다.
                if (best == null || site.cells > best.cells || site.cells == best.cells && before(site.stand, best.stand)) best = site;
            }
        }
        return best;
    }

    /**
     * 그 둑 블록이 자리로 쓸 만한지 확인한다.
     *
     * @param onPedestal 이미 받침을 놓고 그 위에 서 있는지. 그러면 둑 바로 위 칸은 받침이 차지하고 있다
     */
    public static @Nullable ObsidianSite at(TerrainView terrain, Predicate<BlockPoint> isLake, BlockPoint stand, boolean onPedestal) {
        if (terrain.classify(stand.x(), stand.y(), stand.z()) != BlockClass.SOLID) return null;
        // 받침 한 칸과 그 위에 설 두 칸이 비어 있어야 한다.
        for (int up = onPedestal ? 2 : 1; up <= 3; up++) {
            if (terrain.classify(stand.x(), stand.y() + up, stand.z()) != BlockClass.OPEN) return null;
        }
        ObsidianSite best = null;
        for (int[] lake : DIRECTIONS) {
            if (!isOpenSurface(terrain, isLake, stand.offset(lake[0], 0, lake[1]))) continue;
            for (int side = -1; side <= 1; side += 2) {
                // 호수 쪽을 바라봤을 때의 옆 칸
                BlockPoint pour = stand.offset(-lake[1] * side, 0, lake[0] * side);
                if (!isPourSpot(terrain, isLake, pour, lake)) continue;
                if (!isDry(terrain, stand, pour, lake)) continue;
                ObsidianSite site = new ObsidianSite(stand, pour, lake[0], lake[1], reachable(stand, isLake, terrain).size());
                if (best == null || site.cells > best.cells) best = site;
            }
        }
        return best;
    }

    /**
     * 받침 위에서 손이 닿는 호수 표면의 칸 중 조건에 맞는 것. 눈에서 가까운 순서다.
     */
    public static List<BlockPoint> reachable(BlockPoint stand, Predicate<BlockPoint> wanted, TerrainView terrain) {
        List<BlockPoint> cells = new ArrayList<>();
        for (int dx = -WINDOW; dx <= WINDOW; dx++) {
            for (int dz = -WINDOW; dz <= WINDOW; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPoint cell = stand.offset(dx, 0, dz);
                if (eyeDistance(stand, cell) > REACH || !wanted.test(cell)) continue;
                if (terrain.classify(cell.x(), cell.y() + 1, cell.z()) == BlockClass.SOLID) continue;
                cells.add(cell);
            }
        }
        cells.sort((a, b) -> Double.compare(eyeDistance(stand, a), eyeDistance(stand, b)));
        return cells;
    }

    // 받침 위에 선 눈에서 그 칸의 한가운데까지
    public static double eyeDistance(BlockPoint stand, BlockPoint cell) {
        double dx = cell.x() - stand.x();
        double dz = cell.z() - stand.z();
        double dy = EYE_ABOVE_STAND - 0.5 + (stand.y() - cell.y());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean isOpenSurface(TerrainView terrain, Predicate<BlockPoint> isLake, BlockPoint cell) {
        return isLake.test(cell) && terrain.classify(cell.x(), cell.y() + 1, cell.z()) == BlockClass.OPEN;
    }

    // 물을 부을 둑 블록: 단단하고, 위가 비어 있고, 호수와 맞닿아 있어서 부은 물이 바로 용암 위로 흐른다.
    private static boolean isPourSpot(TerrainView terrain, Predicate<BlockPoint> isLake, BlockPoint pour, int[] lake) {
        if (terrain.classify(pour.x(), pour.y(), pour.z()) != BlockClass.SOLID) return false;
        if (terrain.classify(pour.x(), pour.y() + 1, pour.z()) != BlockClass.OPEN
                || terrain.classify(pour.x(), pour.y() + 2, pour.z()) != BlockClass.OPEN) return false;
        return isOpenSurface(terrain, isLake, pour.offset(lake[0], 0, lake[1]));
    }

    /**
     * 둑의 뒤쪽과 양옆에 용암이 없는지. 물은 둑 뒤로도 퍼지는데, 그쪽에 용암이 있으면 조약돌이 생겨서 돌아갈 길이 막히고
     * 그 용암이 받침 쪽으로 밀려올 수도 있다.
     */
    private static boolean isDry(TerrainView terrain, BlockPoint stand, BlockPoint pour, int[] lake) {
        for (BlockPoint rim : new BlockPoint[]{stand, pour}) {
            for (int[] direction : DIRECTIONS) {
                if (direction[0] == lake[0] && direction[1] == lake[1]) continue;
                BlockPoint neighbor = rim.offset(direction[0], 0, direction[1]);
                for (int up = 0; up <= 1; up++) {
                    if (terrain.classify(neighbor.x(), neighbor.y() + up, neighbor.z()) == BlockClass.DANGER) return false;
                }
            }
        }
        return true;
    }

    private static boolean before(BlockPoint a, BlockPoint b) {
        if (a.x() != b.x()) return a.x() < b.x();
        return a.z() < b.z();
    }
}
