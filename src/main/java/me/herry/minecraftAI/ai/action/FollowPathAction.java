package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.List;

/**
 * 이미 파 놓은 굴처럼 정해진 지점들을 차례대로 걸어간다. 지점 사이의 길은 경로 탐색으로 찾는다.
 * 굴이 무너지거나 물에 잠겨서 다음 지점으로 갈 수 없으면 실패한다.
 */
public final class FollowPathAction extends AbstractAction {
    public static final String NAME = "FollowShaft";
    private static final int TIMEOUT = 2400;
    private static final double WAYPOINT_RADIUS = 1.5;

    private final List<BlockPoint> waypoints;
    private int index;

    public FollowPathAction(List<BlockPoint> waypoints) {
        super(NAME, TIMEOUT);
        this.waypoints = List.copyOf(waypoints);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        if (waypoints.isEmpty()) {
            succeed();
            return;
        }
        ai.getNavigation().navigateTo(waypointGoal(waypoints.getFirst()));
    }

    @Override
    protected void onTick(AIPlayer ai) {
        NavigationSystem navigation = ai.getNavigation();
        navigation.tick();
        if (navigation.getState() == NavigationSystem.State.FAILED) {
            fail(navigation.getFailReason());
        } else if (navigation.getState() == NavigationSystem.State.ARRIVED) {
            if (++index >= waypoints.size()) {
                succeed();
                return;
            }
            navigation.navigateTo(waypointGoal(waypoints.get(index)));
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getNavigation().stop();
    }

    public static PathGoal waypointGoal(BlockPoint point) {
        return PathGoal.arrive(point, WAYPOINT_RADIUS);
    }
}
