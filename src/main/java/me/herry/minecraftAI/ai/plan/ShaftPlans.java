package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.FollowPathAction;
import me.herry.minecraftAI.ai.action.WaitAction;
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
    private static final double DEAD_END_RANGE = 2.0;
    // 막다른 굴의 끝에서 이만큼 되돌아간 자리에서 다른 쪽으로 새로 판다.
    private static final int DEAD_END_BACK_OFF = 6;

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

    /**
     * 지금 있는 굴의 위쪽이 가까이에서 막혀 있는지. 굴을 따라 한 구간 위의 지점까지 걸어갈 수 있는지를 본다.
     * 굴 안은 좁아서 닿지 못하는 목적지가 있으면 "갇혔다"는 판정이 나오지만, 굴이 멀쩡하면 갇힌 것이 아니다.
     */
    static boolean wayUpBlocked(AIPlayer ai) {
        BlockPoint feet = ai.getPosition();
        ShaftRegistry.Shaft shaft = ai.getTeam().getShafts().higher(ai.getWorldId(), feet, SHAFT_CLIMB_RANGE, SHAFT_MIN_DEPTH);
        if (shaft == null) return false;
        BlockPoint above = shaft.points().get(Math.max(0, shaft.nearestIndex(feet) - SHAFT_STRIDE));
        return !ShaftAccess.canReach(new BukkitTerrainView(ai.getPlayer().getWorld()), feet, FollowPathAction.waypointGoal(above));
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

    /**
     * 굴의 끝에 서 있는데 어느 쪽으로도 더 팔 수 없을 때 부른다. 처음에는 적어 두고 잠깐 기다렸다가 다시 보게 하고,
     * 되풀이되면 그 굴을 막다른 굴로 치고 몇 단 되돌아간다. 다음 계획에서 그 자리부터 다른 쪽으로 새 굴을 판다.
     * 막다른 굴로 치지 않으면, 다른 데로 옮겨 갔다가도 "이미 파 둔 굴"이라며 이 끝으로 되돌아오기를 끝없이 되풀이한다.
     */
    static List<Action> leaveDeadEnd(AIPlayer ai) {
        ShaftRegistry shafts = ai.getTeam().getShafts();
        BlockPoint feet = ai.getPosition();
        shafts.noteBlockedEnd(ai.getWorldId(), feet, DEAD_END_RANGE, ai.getTicks());
        ShaftRegistry.Shaft dead = shafts.deadEndNear(ai.getWorldId(), feet, DEAD_END_RANGE);
        if (dead == null) return List.of(new WaitAction((int) ShaftRegistry.BLOCKED_RECHECK_TICKS));

        List<BlockPoint> points = dead.points();
        BlockPoint back = points.get(Math.max(0, points.size() - 1 - DEAD_END_BACK_OFF));
        if (back.equals(feet)) return List.of();
        ai.debug("Nothing more to dig at the end of the tunnel at " + dead.end() + ", going back to " + back + " to dig another way");
        return List.of(new FollowPathAction(List.of(back)));
    }

    // 굴을 따라가려다 방금 실패했는지. 굴이 끊기거나 막혔다는 뜻이라 한동안 그 굴에 기대지 않는다.
    static boolean shaftFailedRecently(AIPlayer ai) {
        return ai.getMemory().countRecentFailures(FollowPathAction.NAME, ai.getTicks(), SHAFT_RETRY_WINDOW) > 0;
    }
}
