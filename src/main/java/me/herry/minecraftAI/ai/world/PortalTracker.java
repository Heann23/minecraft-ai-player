package me.herry.minecraftAI.ai.world;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * 포탈로 다른 차원에 도착했을 때, 도착한 쪽의 포탈 위치를 기억에 남긴다. 돌아올 때 그 포탈을 찾아간다.
 * 월드가 바뀌는 순간 두뇌가 초기화되어 행동의 결과로는 남길 수 없으므로 월드 이동 이벤트에서 부른다.
 */
public final class PortalTracker {
    private static final int SEARCH = 2;

    private PortalTracker() {
    }

    public static void onArrival(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BlockPoint portal = portalNear(world, ai.getPosition());
        // 포탈이 아닌 방법(명령 등)으로 옮겨졌으면 남길 것이 없다.
        if (portal == null) return;
        ai.getWorldModel().rememberPortal(WorldModel.PortalKind.NETHER, world.getUID(), portal);
        ai.debug("Arrived through a portal at " + portal + " in " + world.getName());
    }

    // 발 주변의 포탈 칸 중 가장 가까운 것의 맨 아래 칸
    private static @Nullable BlockPoint portalNear(World world, BlockPoint feet) {
        BlockPoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH; dx <= SEARCH; dx++) {
            for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                for (int dy = -SEARCH; dy <= SEARCH; dy++) {
                    BlockPoint cell = feet.offset(dx, dy, dz);
                    if (!isPortal(world, cell)) continue;
                    double distance = cell.distance(feet);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cell;
                    }
                }
            }
        }
        if (best == null) return null;
        while (isPortal(world, best.offset(0, -1, 0))) best = best.offset(0, -1, 0);
        return best;
    }

    private static boolean isPortal(World world, BlockPoint cell) {
        if (cell.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight() || !Positions.isLoaded(world, cell)) return false;
        return Positions.block(world, cell).getType() == Material.NETHER_PORTAL;
    }
}
