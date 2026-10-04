package me.herry.minecraftAI.ai.build;

import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * 집을 지을 자리를 고른다. 특정 좌표에 기대지 않고, 지금 서 있는 곳 주변에서 가장 평평하고 손볼 것이 적은 땅을 찾는다.
 * 물이나 용암이 있는 곳, 나무가 서 있는 곳은 피한다.
 */
public final class SiteFinder {
    private static final int SEARCH_RADIUS = 8;
    // 서 있는 높이에서 위아래로 이만큼까지의 땅을 후보로 본다.
    private static final int MAX_STEP_UP = 2;
    private static final int MAX_STEP_DOWN = 3;
    private static final int HOLE_COST = 2;
    private static final int BUMP_COST = 3;
    // 손볼 것이 이보다 많은 자리는 짓지 않는다. 메우고 깎는 데 재료와 시간이 너무 많이 든다.
    private static final int MAX_COST = 24;
    private static final int NOT_USABLE = Integer.MAX_VALUE;

    private SiteFinder() {
    }

    /**
     * @param feet 지금 서 있는 칸
     * @return 건물의 기준점(실내 한가운데, 서 있는 높이). 지을 만한 자리가 없으면 null.
     */
    public static @Nullable BlockPoint find(World world, BlockPoint feet, Blueprint blueprint) {
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        BlockPoint best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int x = feet.x() + dx;
                int z = feet.z() + dz;
                int y = groundLevel(terrain, x, feet.y(), z);
                if (y == NOT_USABLE) continue;
                // 위가 흙이나 돌로 덮인 자리(굴 안, 절벽 밑)에는 짓지 않는다. 집은 하늘이 보이는 땅 위에 짓는다.
                if (world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) >= y) continue;

                int cost = cost(world, terrain, blueprint, x, y, z);
                if (cost > MAX_COST) continue;
                // 손볼 것이 적은 자리를 먼저, 같으면 가까운 자리를 고른다.
                double score = cost * 4.0 + Math.sqrt(dx * dx + dz * dz) + Math.abs(y - feet.y());
                if (score < bestScore) {
                    bestScore = score;
                    best = new BlockPoint(x, y, z);
                }
            }
        }
        return best;
    }

    // 그 자리에서 발을 딛고 설 수 있는 높이. 서 있는 높이 근처에 그런 칸이 없으면 NOT_USABLE.
    private static int groundLevel(BukkitTerrainView terrain, int x, int baseY, int z) {
        for (int y = baseY + MAX_STEP_UP; y >= baseY - MAX_STEP_DOWN; y--) {
            if (terrain.classify(x, y, z) == BlockClass.OPEN && terrain.classify(x, y - 1, z) == BlockClass.SOLID) return y;
        }
        return NOT_USABLE;
    }

    /**
     * 그 자리에 설계도대로 지으려면 손봐야 하는 양. 지을 수 없는 자리면 NOT_USABLE.
     */
    private static int cost(World world, BukkitTerrainView terrain, Blueprint blueprint, int ox, int oy, int oz) {
        int cost = 0;
        for (Blueprint.Part part : blueprint.parts()) {
            int x = ox + part.dx();
            int y = oy + part.dy();
            int z = oz + part.dz();
            BlockClass blockClass = terrain.classify(x, y, z);
            if (blockClass == BlockClass.UNLOADED || blockClass == BlockClass.WATER || blockClass == BlockClass.DANGER
                    || blockClass == BlockClass.FENCE) return NOT_USABLE;

            boolean solid = blockClass == BlockClass.SOLID;
            if (solid) {
                Material type = world.getBlockAt(x, y, z).getType();
                // 나무를 베어 내면서 짓지는 않는다. 캘 수 없는 블록(기반암 등)이 있는 자리도 안 된다.
                if (Tag.LOGS.isTagged(type) || type.getHardness() < 0.0F) return NOT_USABLE;
            }
            switch (part.role()) {
                case FLOOR -> cost += solid ? 0 : HOLE_COST;
                case CLEAR -> cost += solid ? BUMP_COST : 0;
                default -> {
                    // 벽이나 지붕 자리에 이미 블록이 있으면 그대로 쓰면 되므로 비용이 없다.
                }
            }
        }
        // 문 앞에 서서 드나들 자리가 있어야 한다.
        for (Blueprint.Part door : blueprint.partsOf(BlockRole.DOOR)) {
            int x = ox + door.dx();
            int y = oy + door.dy();
            int z = oz + door.dz() + Integer.signum(door.dz());
            boolean standable = terrain.classify(x, y, z) == BlockClass.OPEN && terrain.classify(x, y + 1, z) == BlockClass.OPEN
                    && terrain.classify(x, y - 1, z) == BlockClass.SOLID;
            if (!standable) return NOT_USABLE;
        }
        return cost;
    }
}
