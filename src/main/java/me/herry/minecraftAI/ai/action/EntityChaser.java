package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * 움직이는 엔티티를 쫓아간다. 따라가기와 공격 행동이 함께 쓴다.
 * 대상이 움직일 때마다 경로를 다시 찾으면 비용이 크므로, 일정 간격을 두고 대상이 충분히 움직였을 때만 다시 찾는다.
 */
final class EntityChaser {
    enum Result { CHASING, IN_RANGE, UNREACHABLE }

    private static final int REPATH_INTERVAL = 10;
    private static final double REPATH_MOVE_SQ = 2.25;
    private static final int MAX_FAILURES = 3;
    private static final double DIRECT_RANGE = 2.5;
    private static final int MAX_DIRECT_TICKS = 40;
    private static final double CLOSE_RADIUS = 1.0;
    private static final double SPRINT_GAP = 1.0;
    private static final int MIN_SPRINT_FOOD = 6;

    // 싸울 상대를 쫓는 것처럼 급한 추격인지
    private final boolean urgent;
    private BlockPoint lastTarget;
    private int sinceRepath;
    private int failures;
    private int directTicks;
    private boolean closeApproach;

    EntityChaser() {
        this(false);
    }

    EntityChaser(boolean urgent) {
        this.urgent = urgent;
    }

    /**
     * 호출하는 쪽에서 대상이 유효하고 같은 월드에 있는지 먼저 확인해야 한다.
     */
    Result tick(AIPlayer ai, Entity target, double stopDistance) {
        NavigationSystem navigation = ai.getNavigation();
        double distance = Positions.distance(ai.getPlayer().getLocation(), target.getLocation());
        if (distance <= stopDistance) {
            if (navigation.isBusy()) navigation.stop();
            lastTarget = null;
            failures = 0;
            directTicks = 0;
            closeApproach = false;
            return Result.IN_RANGE;
        }

        if (navigation.getState() == NavigationSystem.State.FAILED && lastTarget != null) {
            lastTarget = null;
            if (++failures >= MAX_FAILURES) return Result.UNREACHABLE;
        }

        // 경로상으로는 도착했지만 아직 거리가 조금 남았으면 경로를 다시 찾지 않고 대상을 향해 곧장 걷는다.
        boolean busy = navigation.isBusy();
        if (!busy && navigation.getState() == NavigationSystem.State.ARRIVED && distance <= stopDistance + DIRECT_RANGE) {
            if (++directTicks <= MAX_DIRECT_TICKS) {
                Location location = target.getLocation();
                AIBody body = ai.getBody();
                body.lookAt(location.getX(), location.getY() + target.getHeight() * 0.5, location.getZ());
                body.inputMove(1.0F, 0.0F);
                // 상대가 물러나고 있으면 걸어서는 거리가 줄지 않는다. 한 걸음 넘게 남았으면 달려서 붙는다.
                body.inputSprint(distance > stopDistance + SPRINT_GAP && ai.getPlayer().getFoodLevel() > MIN_SPRINT_FOOD);
                body.inputJump(ai.getPlayer().isInWater() || (body.isGrounded() && body.isBlockedHorizontally()));
                return Result.CHASING;
            }
            // 곧장 걸어서는 닿지 못했다 (높이가 다르거나 사이에 벽이 있다). 대상 바로 옆까지 가는 경로를 다시 찾는다.
            directTicks = 0;
            closeApproach = true;
            if (++failures >= MAX_FAILURES) return Result.UNREACHABLE;
        }

        BlockPoint targetBlock = Positions.of(target.getLocation());
        sinceRepath++;
        boolean targetMoved = lastTarget == null || lastTarget.distanceSq(targetBlock) > REPATH_MOVE_SQ;
        // 경로를 찾는 중에는 다시 찾지 않는다. 대상이 계속 움직이면 탐색이 끝나기 전에 매번 새로 시작해서 한 걸음도 못 가게 된다.
        boolean following = navigation.getState() == NavigationSystem.State.FOLLOWING;
        if (!busy || (following && targetMoved && sinceRepath >= REPATH_INTERVAL)) {
            double radius = closeApproach ? CLOSE_RADIUS : Math.max(1.5, stopDistance - 0.5);
            navigation.navigateTo(PathGoal.arrive(targetBlock, radius));
            // 움직이는 상대는 경로의 끝이 계속 달아난다. 끝에서 속도를 줄이지 않고 바로 앞까지 달린다.
            navigation.setChasing(urgent);
            lastTarget = targetBlock;
            sinceRepath = 0;
        }
        navigation.tick();
        return Result.CHASING;
    }
}
