package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.GroundFinder;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 위협에서 멀어지는 방향으로 달아난다.
 */
public final class RunAwayAction extends AbstractAction {
    private static final int TIMEOUT = 200;
    // 정면이 막혀 있으면 좌우로 방향을 틀어서 도망갈 곳을 찾는다.
    private static final double[] ANGLE_OFFSETS = {0.0, 35.0, -35.0, 70.0, -70.0, 110.0, -110.0};
    private static final int MAX_RETARGETS = 3;
    private static final int DIRECT_RUN_TICKS = 60;

    private final @Nullable Entity threat;
    private final Location origin;
    private final double distance;
    private int retargets;
    private int directTicks = -1;

    /**
     * @param threat 쫓아오는 엔티티. 지형처럼 움직이지 않는 위협이면 null 을 넘기고 origin 을 기준으로 삼는다.
     */
    public RunAwayAction(@Nullable Entity threat, Location origin, double distance) {
        super("RunAway", TIMEOUT);
        this.threat = threat;
        this.origin = origin.clone();
        this.distance = distance;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        if (!navigateAway(ai)) directTicks = 0;
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        Location from = threatLocation(player);
        // 위협이 다른 월드에 있으면 거리가 무한대로 계산되어 바로 성공한다.
        if (Positions.distance(player.getLocation(), from) >= distance) {
            succeed();
            return;
        }

        if (directTicks >= 0) {
            runDirectly(ai, player, from);
            return;
        }

        NavigationSystem navigation = ai.getNavigation();
        navigation.tick();
        if (navigation.getState() == NavigationSystem.State.ARRIVED) {
            // 도착했는데도 아직 가까우면 (위협이 쫓아왔으면) 다시 도망갈 곳을 찾는다.
            if (++retargets > MAX_RETARGETS || !navigateAway(ai)) succeed();
        } else if (navigation.getState() == NavigationSystem.State.FAILED) {
            directTicks = 0;
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getNavigation().stop();
    }

    private Location threatLocation(Player player) {
        if (threat != null && threat.isValid() && threat.getWorld().equals(player.getWorld())) return threat.getLocation();
        return origin;
    }

    private boolean navigateAway(AIPlayer ai) {
        Player player = ai.getPlayer();
        Location location = player.getLocation();
        Location from = threatLocation(player);

        double dx = location.getX() - from.getX();
        double dz = location.getZ() - from.getZ();
        if (!Positions.sameWorld(location, from) || dx * dx + dz * dz < 1.0E-4) {
            // 위협과 겹쳐 있으면 방향을 정할 수 없으므로 지금 바라보는 방향으로 달린다.
            double yaw = Math.toRadians(location.getYaw());
            dx = -Math.sin(yaw);
            dz = Math.cos(yaw);
        }
        double baseAngle = Math.atan2(dz, dx);

        BukkitTerrainView terrain = new BukkitTerrainView(player.getWorld());
        for (double scale : new double[]{1.0, 0.6}) {
            for (double offset : ANGLE_OFFSETS) {
                double angle = baseAngle + Math.toRadians(offset);
                int x = (int) Math.floor(location.getX() + Math.cos(angle) * distance * scale);
                int z = (int) Math.floor(location.getZ() + Math.sin(angle) * distance * scale);
                BlockPoint ground = GroundFinder.find(terrain, x, location.getBlockY(), z, 5, 8);
                if (ground == null) continue;
                ai.getNavigation().navigateTo(PathGoal.arrive(ground, 2.0));
                return true;
            }
        }
        return false;
    }

    // 길을 찾지 못했을 때의 차선책. 위협의 반대 방향으로 그냥 달린다.
    private void runDirectly(AIPlayer ai, Player player, Location from) {
        if (++directTicks > DIRECT_RUN_TICKS) {
            succeed();
            return;
        }
        AIBody body = ai.getBody();
        Location location = player.getLocation();
        double dx = location.getX() - from.getX();
        double dz = location.getZ() - from.getZ();
        if (dx * dx + dz * dz > 1.0E-4) body.inputLook(Positions.yawTo(dx, dz), 0.0F);
        body.inputMove(1.0F, 0.0F);
        body.inputSprint(player.getFoodLevel() > 6);
        body.inputJump(player.isInWater() || (body.isGrounded() && body.isBlockedHorizontally()));
    }
}
