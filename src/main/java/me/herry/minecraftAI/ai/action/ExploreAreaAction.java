package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.memory.MemoryEntry;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.FrontierChoice;
import me.herry.minecraftAI.ai.navigation.GroundFinder;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 아직 가 보지 않은 방향으로 얼마간 걸어가서 새로운 지역을 살핀다.
 */
public final class ExploreAreaAction extends AbstractAction {
    private static final int TIMEOUT = 2200;
    private static final int LAND_TIMEOUT = 500;
    private static final int SECTORS = 12;
    private static final int RINGS = 2;
    private static final int SAMPLES_PER_TICK = 3;
    private static final double FAILED_TARGET_RADIUS_SQ = 16.0;
    private static final double NEAR_FAILURE_RADIUS_SQ = 144.0;
    private static final double RECENT_PATH_RADIUS_SQ = 64.0;
    // 지나가 본 청크가 이만큼은 쌓여야 "덜 가 본 방향"을 고를 근거가 된다.
    private static final int MIN_EXPLORED_FOR_MAP = 6;

    private final double minDistance;
    private final double maxDistance;
    private final boolean stayUnderground;
    private Location startLocation;
    private UUID targetWorld;
    private BukkitTerrainView terrain;
    private double preferred;
    private double sectorOffset;
    private int sampleIndex;
    private boolean failureRemembered;
    private List<MemorySystem.Visit> recentPath = List.of();
    private List<MemoryEntry> failedApproaches = List.of();
    private final List<FrontierChoice.Candidate> candidates = new ArrayList<>();
    private FrontierChoice.Candidate selected;
    private SurfaceTravelAction.CandidateSearch waterSearch;
    private PrimitiveTarget.Point waterTarget;
    private SurfaceTravelAction surfaceTravel;

    public ExploreAreaAction(double minDistance, double maxDistance) {
        this(minDistance, maxDistance, false);
    }

    private ExploreAreaAction(double minDistance, double maxDistance, boolean stayUnderground) {
        super("ExploreArea", TIMEOUT);
        if (!Double.isFinite(minDistance) || !Double.isFinite(maxDistance) || minDistance <= 0 || maxDistance < minDistance) {
            throw new IllegalArgumentException("invalid exploration range");
        }
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
        ai.getNavigation().stop();
        startLocation = ai.getPlayer().getLocation();
        targetWorld = ai.getWorldId();
        terrain = new BukkitTerrainView(ai.getPlayer().getWorld());
        preferred = preferredDirection(ai, startLocation);
        sectorOffset = ThreadLocalRandom.current().nextDouble(-Math.PI / SECTORS, Math.PI / SECTORS);
        recentPath = ai.getMemory().getRecentPath().stream().filter(visit -> visit.world().equals(targetWorld)).toList();
        failedApproaches = ai.getMemory().all(MemoryType.UNREACHABLE, targetWorld, ai.getTicks());
        if (!stayUnderground) waterSearch = new SurfaceTravelAction.CandidateSearch(ai, true);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        if (!ai.getWorldId().equals(targetWorld)) {
            fail("world changed");
            return;
        }
        if (selected == null) {
            sampleTargets(ai);
            if (waterSearch != null && waterSearch.searching()) waterSearch.advance(ai, 2);
            if (sampleIndex < SECTORS * RINGS || waterSearch != null && waterSearch.searching()) return;
            if (waterSearch != null && waterSearch.target() != null) {
                waterTarget = waterSearch.target();
                BlockPoint point = new BlockPoint((int) Math.floor(waterTarget.x()), (int) Math.floor(waterTarget.y()),
                        (int) Math.floor(waterTarget.z()));
                double angle = Math.atan2(point.z() - startLocation.getZ(), point.x() - startLocation.getX());
                candidates.add(describe(ai, point, angle));
            }
            selected = FrontierChoice.select(candidates).orElse(null);
            if (selected == null) {
                fail("no safe loaded frontier endpoint in sampled area");
                return;
            }
            ai.debug("Exploring toward " + selected.target() + " (visited chunk=" + selected.exploredChunk() + ")");
            if (waterTarget != null && selected.target().x() == (int) Math.floor(waterTarget.x())
                    && selected.target().y() == (int) Math.floor(waterTarget.y())
                    && selected.target().z() == (int) Math.floor(waterTarget.z())) {
                surfaceTravel = new SurfaceTravelAction(targetWorld, waterTarget, 1800);
            } else ai.getNavigation().navigateTo(PathGoal.arrive(selected.target(), 3.0));
            terrain = null;
            waterSearch = null;
            candidates.clear();
        }
        if (surfaceTravel != null) {
            surfaceTravel.update(ai);
            if (surfaceTravel.getStatus() == ActionStatus.SUCCESS) {
                ai.getMemory().clearUnreachable(targetWorld, selected.target());
                succeed();
            } else if (surfaceTravel.getStatus() == ActionStatus.FAILED) {
                rememberFailedApproach(ai);
                fail(surfaceTravel.getFailReason());
            }
            return;
        }
        if (getElapsed() > LAND_TIMEOUT) { fail("land exploration timed out"); return; }
        NavigationSystem navigation = ai.getNavigation();
        navigation.tick();

        if (navigation.getState() == NavigationSystem.State.ARRIVED) {
            ai.getMemory().clearUnreachable(targetWorld, selected.target());
            succeed();
        } else if (navigation.getState() == NavigationSystem.State.FAILED) {
            rememberFailedApproach(ai);
            // Moving a few blocks inside the same familiar area is not successful exploration.
            if (FrontierChoice.reachedNewFrontier(selected, Positions.of(startLocation), ai.getPosition())) succeed();
            else fail(navigation.getFailReason());
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        if (selected != null && getStatus() == ActionStatus.FAILED && !getFailReason().equals("cancelled")
                && !getFailReason().equals("world changed") && ai.getWorldId().equals(targetWorld)) rememberFailedApproach(ai);
        ai.getNavigation().stop();
        if (surfaceTravel != null && surfaceTravel.getStatus() == ActionStatus.RUNNING) surfaceTravel.cancel(ai);
        surfaceTravel = null;
        waterSearch = null;
        waterTarget = null;
        terrain = null;
        candidates.clear();
        recentPath = List.of();
        failedApproaches = List.of();
    }

    private void rememberFailedApproach(AIPlayer ai) {
        if (failureRemembered) return;
        ai.getMemory().rememberUnreachable(targetWorld, selected.target(), ai.getTicks());
        failureRemembered = true;
    }

    private void sampleTargets(AIPlayer ai) {
        Player player = ai.getPlayer();
        World world = player.getWorld();
        terrain.clearCache();
        int remaining = SAMPLES_PER_TICK;
        while (sampleIndex < SECTORS * RINGS && remaining-- > 0 && !ai.getWorkBudget().isExhausted()) {
            int sector = sampleIndex % SECTORS;
            int ring = sampleIndex++ / SECTORS;
            double angle = preferred + sectorOffset + sector * Math.PI * 2.0 / SECTORS;
            double range = ring == 0 ? maxDistance : minDistance;
            int x = (int) Math.floor(startLocation.getX() + Math.cos(angle) * range);
            int z = (int) Math.floor(startLocation.getZ() + Math.sin(angle) * range);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            int surface = world.getHighestBlockYAt(x, z) + 1;
            BlockPoint ground;
            if (stayUnderground) {
                // 동굴을 탐험할 때는 지금 높이 근처에서, 위가 막혀 있는(지상이 아닌) 칸만 고른다.
                ground = GroundFinder.find(terrain, x, startLocation.getBlockY(), z, 4, 8);
                if (ground != null && ground.y() >= surface - 2) ground = null;
            } else {
                // Land endpoints are evaluated here; observed water routes use the same action lane separately.
                ground = GroundFinder.find(terrain, x, surface, z, 2, 6);
                if (ground == null) ground = GroundFinder.find(terrain, x, startLocation.getBlockY(), z, 4, 6);
            }
            if (ground != null) candidates.add(describe(ai, ground, angle));
        }
    }

    private FrontierChoice.Candidate describe(AIPlayer ai, BlockPoint ground, double angle) {
        int chunkX = ground.x() >> 4;
        int chunkZ = ground.z() >> 4;
        int unexploredNeighbors = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if ((dx != 0 || dz != 0) && !ai.getWorldModel().isExplored(targetWorld, chunkX + dx, chunkZ + dz)) unexploredNeighbors++;
        }
        int recentVisits = 0;
        for (MemorySystem.Visit visit : recentPath) if (visit.pos().distanceSq(ground) <= RECENT_PATH_RADIUS_SQ) recentVisits++;
        boolean failedTarget = false;
        int nearbyFailures = 0;
        for (MemoryEntry failure : failedApproaches) {
            double distance = failure.pos().distanceSq(ground);
            if (distance <= FAILED_TARGET_RADIUS_SQ) failedTarget = true;
            if (distance <= NEAR_FAILURE_RADIUS_SQ) nearbyFailures++;
        }
        return new FrontierChoice.Candidate(ground, ai.getWorldModel().isExplored(targetWorld, chunkX, chunkZ),
                unexploredNeighbors, recentVisits, failedTarget, nearbyFailures,
                ground.distance(Positions.of(startLocation)), Math.cos(angle - preferred));
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
