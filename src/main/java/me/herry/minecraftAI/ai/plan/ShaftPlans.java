package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.FollowPathAction;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.ShaftAccess;
import me.herry.minecraftAI.ai.team.ShaftRegistry;
import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 이미 파 둔 굴(계단과 수평 굴)을 따라 오르내리는 계획. 굴을 새로 파는 일은 TerrainPlans 가 맡는다.
 */
final class ShaftPlans {
    // 굴의 어느 지점이든 이 거리 안에 있으면 그 굴을 따라 내려간다. 올라갈 때는 굴 안이나 바로 옆에 있어야 한다.
    private static final double SHAFT_ACCESS_RANGE = 40.0;
    private static final double SHAFT_CLIMB_RANGE = 12.0;
    private static final int SHAFT_MIN_DEPTH = 4;
    // 굴을 따라갈 때 한 번에 경로를 찾는 구간의 길이
    private static final int SHAFT_STRIDE = 12;
    private static final long SHAFT_RETRY_WINDOW = 600L;

    private ShaftPlans() {
    }

    /**
     * 이미 파 둔 굴(자기 것이든 동료 것이든)을 따라 더 깊은 곳으로 내려간다.
     * 쓸 만한 굴이 없거나 방금 그 굴이 막혀 있었으면 빈 목록이다. 그때는 새로 판다.
     */
    static List<Action> followShaftDown(AIPlayer ai) {
        if (shaftFailedRecently(ai)) return List.of();
        BlockPoint feet = ai.getPosition();
        ShaftRegistry.Shaft shaft = ai.getTeam().getShafts().deeper(ai.getWorldId(), feet, SHAFT_ACCESS_RANGE, SHAFT_MIN_DEPTH);
        if (shaft == null) return List.of();

        List<BlockPoint> points = shaft.points();
        List<BlockPoint> waypoints = new ArrayList<>();
        for (int i = shaft.nearestIndex(feet); i < points.size() - 1; i += SHAFT_STRIDE) waypoints.add(points.get(i));
        waypoints.add(shaft.end());
        // 굴 바로 곁에 있는데도 그 굴로 들어설 길이 없으면(옆으로 파 들어간 자리, 단차 아래 등) 따라가려 하지 않고 새로 판다.
        // 멀리 있는 굴은 가는 길이 길어서 여기서 확인하지 않는다.
        BlockPoint first = waypoints.getFirst();
        if (first.distance(feet) <= SHAFT_CLIMB_RANGE
                && !ShaftAccess.canReach(new BukkitTerrainView(ai.getPlayer().getWorld()), feet, FollowPathAction.waypointGoal(first))) {
            ai.debug("Nearby tunnel has no reachable access from " + feet + ", digging a new way down");
            return List.of();
        }
        ai.debug("Following " + shaft.owner() + "'s tunnel down to " + shaft.end());
        return List.of(new FollowPathAction(waypoints));
    }

    // 지금 있는 굴을 따라 입구까지 걸어 올라간다.
    static List<Action> followShaftUp(AIPlayer ai) {
        if (shaftFailedRecently(ai)) return List.of();
        BlockPoint feet = ai.getPosition();
        ShaftRegistry.Shaft shaft = ai.getTeam().getShafts().higher(ai.getWorldId(), feet, SHAFT_CLIMB_RANGE, SHAFT_MIN_DEPTH);
        if (shaft == null) return List.of();

        List<BlockPoint> points = shaft.points();
        List<BlockPoint> waypoints = new ArrayList<>();
        for (int i = shaft.nearestIndex(feet); i > 0; i -= SHAFT_STRIDE) waypoints.add(points.get(i));
        waypoints.add(shaft.entrance());
        BukkitTerrainView terrain = new BukkitTerrainView(ai.getPlayer().getWorld());
        if (!ShaftAccess.canReach(terrain, feet, FollowPathAction.waypointGoal(waypoints.getFirst()))) {
            ai.debug("Nearby tunnel has no reachable access from " + feet + ", digging a new way up");
            return List.of();
        }
        ai.debug("Walking up the tunnel to " + shaft.entrance());
        return List.of(new FollowPathAction(waypoints));
    }

    // 굴을 따라가려다 방금 실패했는지. 굴이 끊기거나 막혔다는 뜻이라 한동안 그 굴에 기대지 않는다.
    static boolean shaftFailedRecently(AIPlayer ai) {
        return ai.getMemory().countRecentFailures(FollowPathAction.NAME, ai.getTicks(), SHAFT_RETRY_WINDOW) > 0;
    }
}
