package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.GroundFinder;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 아직 가 보지 않은 방향으로 얼마간 걸어가서 새로운 지역을 살핀다.
 */
public final class ExploreAreaAction extends AbstractAction {
    private static final int TIMEOUT = 500;
    private static final int ATTEMPTS = 8;
    // 이만큼도 못 움직였으면 탐험이 실패한 것으로 본다.
    private static final double MIN_PROGRESS = 4.0;
    // 지나가 본 청크가 이만큼은 쌓여야 "덜 가 본 방향"을 고를 근거가 된다.
    private static final int MIN_EXPLORED_FOR_MAP = 6;

    private final double minDistance;
    private final double maxDistance;
    private final boolean stayUnderground;
    private Location startLocation;

    public ExploreAreaAction(double minDistance, double maxDistance) {
        this(minDistance, maxDistance, false);
    }

    private ExploreAreaAction(double minDistance, double maxDistance, boolean stayUnderground) {
        super("ExploreArea", TIMEOUT);
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
        this.stayUnderground = stayUnderground;
    }

    // 지상으로 올라가지 않고 동굴 안에서만 돌아다니는 탐험.
    public static ExploreAreaAction cave(double minDistance, double maxDistance) {
        return new ExploreAreaAction(minDistance, maxDistance, true);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        startLocation = ai.getPlayer().getLocation();
        BlockPoint target = pickTarget(ai);
        if (target == null) {
            fail("nowhere to explore");
            return;
        }
        ai.debug("Exploring toward " + target);
        ai.getNavigation().navigateTo(PathGoal.arrive(target, 3.0));
    }

    @Override
    protected void onTick(AIPlayer ai) {
        NavigationSystem navigation = ai.getNavigation();
        navigation.tick();

        if (navigation.getState() == NavigationSystem.State.ARRIVED) {
            succeed();
        } else if (navigation.getState() == NavigationSystem.State.FAILED) {
            // 끝까지 가지 못했더라도 새로운 곳으로 어느 정도 이동했으면 탐험으로서는 성공이다.
            if (Positions.distance(ai.getPlayer().getLocation(), startLocation) >= MIN_PROGRESS) succeed();
            else fail(navigation.getFailReason());
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getNavigation().stop();
    }

    private @Nullable BlockPoint pickTarget(AIPlayer ai) {
        Player player = ai.getPlayer();
        World world = player.getWorld();
        Location location = player.getLocation();
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double preferred = preferredDirection(ai, location);

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            // 처음에는 덜 가 본 쪽을 고르고, 실패할수록 방향을 넓게 흩뜨린다.
            double spread = Math.toRadians(40.0 + attempt * 40.0);
            double angle = preferred + (random.nextDouble() * 2.0 - 1.0) * spread;
            double range = minDistance + random.nextDouble() * (maxDistance - minDistance);
            int x = (int) Math.floor(location.getX() + Math.cos(angle) * range);
            int z = (int) Math.floor(location.getZ() + Math.sin(angle) * range);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            int surface = world.getHighestBlockYAt(x, z) + 1;
            if (stayUnderground) {
                // 동굴을 탐험할 때는 지금 높이 근처에서, 위가 막혀 있는(지상이 아닌) 칸만 고른다.
                BlockPoint ground = GroundFinder.find(terrain, x, location.getBlockY(), z, 4, 8);
                if (ground != null && ground.y() < surface - 2) return ground;
                continue;
            }

            // 지표면 높이에서 시작해서 설 수 있는 칸을 찾는다. 동굴 안이라면 현재 높이 근처도 살펴본다.
            BlockPoint ground = GroundFinder.find(terrain, x, surface, z, 2, 6);
            if (ground == null) ground = GroundFinder.find(terrain, x, location.getBlockY(), z, 4, 6);
            if (ground != null) return ground;
        }
        return null;
    }

    /**
     * 탐험할 방향(라디안). 지상에서는 지금까지 지나가 본 청크의 기록을 보고 가장 덜 가 본 쪽을 고른다.
     * 동굴 안이거나 아직 돌아다닌 곳이 별로 없으면 방금 지나온 길의 반대쪽으로 간다.
     */
    private double preferredDirection(AIPlayer ai, Location location) {
        UUID world = ai.getWorldId();
        if (stayUnderground || ai.getWorldModel().exploredCount(world) < MIN_EXPLORED_FOR_MAP) return awayFromRecentPath(ai, location);
        int[] direction = ai.getWorldModel().leastExploredDirection(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
        return Math.atan2(direction[1], direction[0]);
    }

    // 최근에 지나온 위치들의 중심에서 멀어지는 방향(라디안). 기록이 없으면 무작위 방향이다.
    private static double awayFromRecentPath(AIPlayer ai, Location location) {
        List<MemorySystem.Visit> visits = ai.getMemory().getRecentPath();
        UUID world = ai.getWorldId();
        double sumX = 0.0;
        double sumZ = 0.0;
        int count = 0;
        for (MemorySystem.Visit visit : visits) {
            if (!visit.world().equals(world)) continue;
            sumX += visit.pos().x();
            sumZ += visit.pos().z();
            count++;
        }

        if (count > 0) {
            double dx = location.getX() - sumX / count;
            double dz = location.getZ() - sumZ / count;
            if (dx * dx + dz * dz > 4.0) return Math.atan2(dz, dx);
        }
        return ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
    }
}
