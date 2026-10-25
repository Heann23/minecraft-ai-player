package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.SurfaceRoute;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** An observed water-surface leg through ordinary movement and jump inputs, without forcing swimming or loading chunks. */
public final class SurfaceTravelAction extends AbstractAction implements PrimitiveAction {
    private static final int MIN_AIR = 60;
    private static final int MIN_FOOD = 6;
    private static final double MIN_HEALTH = 8.0;
    private static final int SEARCH_PER_TICK = 12;
    private final UUID targetWorld;
    private final PrimitiveTarget.Point target;
    private final BlockPoint destination;
    private World world;
    private SurfaceRoute route;
    private List<BlockPoint> path = List.of();
    private int next;
    private double checkpointDistance;
    private int stalled;

    /** target.y is the observed water block y + 1.0, also the feet height of a level shore endpoint. */
    public SurfaceTravelAction(UUID targetWorld, PrimitiveTarget.Point target, int timeoutTicks) {
        super("SurfaceTravel", duration(timeoutTicks));
        this.targetWorld = Objects.requireNonNull(targetWorld, "targetWorld");
        this.target = Objects.requireNonNull(target, "target");
        if (!Double.isFinite(target.x()) || !Double.isFinite(target.y()) || !Double.isFinite(target.z())
                || Math.abs(target.x()) > 30_000_000 || Math.abs(target.z()) > 30_000_000)
            throw new IllegalArgumentException("invalid surface destination");
        destination = new BlockPoint((int) Math.floor(target.x()), (int) Math.floor(target.y()) - 1,
                (int) Math.floor(target.z()));
    }

    private static int duration(int ticks) {
        if (ticks < 1 || ticks > 2400) throw new IllegalArgumentException("surface timeout must be 1..2400");
        return ticks;
    }

    @Override protected void onStart(AIPlayer ai) {
        ai.getNavigation().stop();
        Player player = ai.getPlayer();
        world = player.getWorld();
        if (!ai.getWorldId().equals(targetWorld) || !canTravel(ai)) { fail("surface travel unavailable"); return; }
        BlockPoint start = new BlockPoint(player.getLocation().getBlockX(), destination.y(), player.getLocation().getBlockZ());
        double distance = horizontalSquared(player, destination);
        if (distance > SurfaceRoute.MAX_RANGE * SurfaceRoute.MAX_RANGE
                || player.getLocation().getY() < destination.y() - 2 || player.getLocation().getY() > destination.y() + 1.5
                || !SurfaceRoute.passable(column(world, start.x(), start.y(), start.z()))) {
            fail("surface leg requires a nearby loaded surface or shore");
            return;
        }
        route = new SurfaceRoute(start, destination, (x, z) -> column(world, x, destination.y(), z));
        checkpointDistance = distance;
        ai.getBody().inputSneak(false);
        ai.getBody().inputSprint(false);
    }

    @Override protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!ai.getWorldId().equals(targetWorld) || !ai.getBody().isUsable()) { fail("body unavailable or world changed"); return; }
        if (!canTravel(ai)) { fail("surface travel safety condition changed"); return; }
        var location = player.getLocation();
        SurfaceRoute.Kind currentColumn = column(world, location.getBlockX(), destination.y(), location.getBlockZ());
        if (!SurfaceRoute.passable(currentColumn)) { fail("surface changed or unavailable"); return; }
        SurfaceRoute.Kind end = column(world, destination.x(), destination.y(), destination.z());
        if (!SurfaceRoute.passable(end)) { fail("surface destination changed or unloaded"); return; }
        boolean eyesUnderWater = player.getEyeLocation().getBlock().isLiquid();
        if (SurfaceRoute.arrived(location.getX(), location.getY(), location.getZ(), destination,
                player.isInWater(), ai.getBody().isGrounded(), eyesUnderWater,
                player.getRemainingAir(), player.getMaximumAir())) {
            ai.getMemory().clearUnreachable(targetWorld, destination.offset(0, 1, 0));
            succeed();
            return;
        }
        // Jump is ordinary upward water input. Never hold sneak or sprint to dive while planning or moving.
        ai.getBody().inputSneak(false);
        ai.getBody().inputSprint(false);
        ai.getBody().inputJump(player.isInWater() || currentColumn == SurfaceRoute.Kind.SHORE);
        if (path.isEmpty()) {
            ai.getBody().inputMove(0.0F, 0.0F);
            SurfaceRoute.Status result = route.advance(SEARCH_PER_TICK, ai.getWorkBudget()::isExhausted);
            if (result == SurfaceRoute.Status.SEARCHING) return;
            if (result != SurfaceRoute.Status.FOUND) {
                fail(result == SurfaceRoute.Status.LIMIT_REACHED ? "surface route search limit" :
                        route.encounteredUnknown() ? "surface route not confirmed in loaded observations" : "no supported surface route");
                return;
            }
            path = route.path();
            next = Math.min(1, path.size() - 1);
            checkpointDistance = remainingDistance(player, path.get(next));
        }
        BlockPoint waypoint = path.get(next);
        if (!SurfaceRoute.passable(column(world, waypoint.x(), waypoint.y(), waypoint.z()))) {
            fail("surface route changed");
            return;
        }
        if (horizontalSquared(player, waypoint) <= 0.09 && next < path.size() - 1) waypoint = path.get(++next);
        if (!SurfaceRoute.passable(column(world, waypoint.x(), waypoint.y(), waypoint.z()))) {
            fail("surface route changed");
            return;
        }
        // Check the very next physical column as well: a lateral flow/collision can move the body off its planned line.
        double dx = waypoint.x() + 0.5 - location.getX(), dz = waypoint.z() + 0.5 - location.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length > 0.01) {
            int aheadX = (int) Math.floor(location.getX() + dx / length * 0.8);
            int aheadZ = (int) Math.floor(location.getZ() + dz / length * 0.8);
            if (!SurfaceRoute.passable(column(world, aheadX, destination.y(), aheadZ))) {
                fail("unsafe surface ahead");
                return;
            }
        }
        double eyeY = player.getEyeLocation().getY();
        ai.getBody().lookAt(waypoint.x() + 0.5, eyeY, waypoint.z() + 0.5);
        boolean facing = ai.getBody().isFacing(waypoint.x() + 0.5, eyeY, waypoint.z() + 0.5, 30.0F);
        ai.getBody().inputMove(facing ? 1.0F : 0.0F, 0.0F);
        if (getElapsed() % 20 == 0) {
            double distance = remainingDistance(player, waypoint);
            stalled = distance >= checkpointDistance - 0.1 ? stalled + 20 : 0;
            checkpointDistance = distance;
            if (stalled >= 100 || ai.getBody().isBlockedHorizontally() && stalled >= 60) fail("surface travel blocked");
        }
    }

    @Override protected void onEnd(AIPlayer ai) {
        if (getStatus() == ActionStatus.FAILED && ai.getWorldId().equals(targetWorld)
                && (getFailReason().equals("surface travel blocked") || getFailReason().equals("unsafe surface ahead")
                || getFailReason().equals("no supported surface route") || getFailReason().equals("surface route changed")))
            ai.getMemory().rememberUnreachable(targetWorld, destination.offset(0, 1, 0), ai.getTicks());
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
        path = List.of();
    }

    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.SWIM; }
    @Override public PrimitiveTarget getTarget() { return target; }

    public static boolean canTravel(AIPlayer ai) {
        Player player = ai.getPlayer();
        return ai.getBody().isUsable() && !player.isInsideVehicle() && !player.isInLava()
                && player.getHealth() > MIN_HEALTH && player.getFoodLevel() > MIN_FOOD
                && player.getRemainingAir() > MIN_AIR && player.getFreezeTicks() < player.getMaxFreezeTicks();
    }

    /** Loaded observations only. Source-water and breathable head room deliberately exclude rapids, waterfalls and ice roofs. */
    private static SurfaceRoute.Kind column(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) return SurfaceRoute.Kind.DANGER;
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return SurfaceRoute.Kind.UNKNOWN;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return SurfaceRoute.Kind.UNKNOWN;
            for (int dy = -1; dy <= 1; dy++) {
                var neighbor = world.getBlockAt(x + dx, y + dy, z + dz);
                if (BukkitTerrainView.classify(neighbor.getType()) == BlockClass.DANGER) return SurfaceRoute.Kind.DANGER;
                if (dy == 0 && neighbor.getType() == Material.WATER
                        && neighbor.getBlockData() instanceof Levelled level && level.getLevel() != 0) return SurfaceRoute.Kind.DANGER;
            }
        }
        var base = world.getBlockAt(x, y, z);
        if (BukkitTerrainView.classify(world.getBlockAt(x, y + 1, z).getType()) != BlockClass.OPEN
                || BukkitTerrainView.classify(world.getBlockAt(x, y + 2, z).getType()) != BlockClass.OPEN)
            return SurfaceRoute.Kind.BLOCKED;
        if (base.getType() == Material.WATER && base.getBlockData() instanceof Levelled level && level.getLevel() == 0)
            return SurfaceRoute.Kind.WATER;
        return BukkitTerrainView.classify(base.getType()) == BlockClass.SOLID
                ? SurfaceRoute.Kind.SHORE : SurfaceRoute.Kind.BLOCKED;
    }

    private static double horizontalSquared(Player player, BlockPoint point) {
        var position = player.getLocation();
        double dx = position.getX() - point.x() - 0.5, dz = position.getZ() - point.z() - 0.5;
        return dx * dx + dz * dz;
    }

    private double remainingDistance(Player player, BlockPoint waypoint) {
        return path.size() - next - 1 + Math.sqrt(horizontalSquared(player, waypoint));
    }

    /** Convenience only; a null result can include a yielded budget. Use CandidateSearch to distinguish that state. */
    public static @Nullable PrimitiveTarget.Point findCandidate(AIPlayer ai, boolean unexploredOnly) {
        CandidateSearch search = new CandidateSearch(ai, unexploredOnly);
        search.advance(ai, 64);
        return search.target();
    }

    /** Candidate endpoints are opportunities, not proofs of a continuous route; the action must find and recheck that route. */
    public static final class CandidateSearch {
        public enum Status { SEARCHING, FOUND, NONE, STALE }
        private static final int[][] NEAR = {{0, 0}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        private static final int PROBES = NEAR.length * 4;
        private static final int SECTORS = 12;
        private static final int RINGS = 3;
        private final UUID world;
        private final BlockPoint origin;
        private final boolean unexploredOnly;
        private int probe;
        private int sample;
        private @Nullable Integer waterY;
        private @Nullable PrimitiveTarget.Point best;
        private double bestScore = Double.NEGATIVE_INFINITY;
        private Status status = Status.SEARCHING;
        private boolean unknown;

        public CandidateSearch(AIPlayer ai, boolean unexploredOnly) {
            world = ai.getWorldId();
            origin = ai.getPosition();
            this.unexploredOnly = unexploredOnly;
        }

        public Status status() { return status; }
        public boolean searching() { return status == Status.SEARCHING; }
        public boolean encounteredUnknown() { return unknown; }
        public @Nullable PrimitiveTarget.Point target() { return status == Status.FOUND ? best : null; }

        public Status advance(AIPlayer ai, int maxSamples) {
            if (maxSamples < 0) throw new IllegalArgumentException("negative sample budget");
            if (status != Status.SEARCHING) return status;
            if (!world.equals(ai.getWorldId()) || horizontalDistance(origin, ai.getPosition()) > 9.0) {
                status = Status.STALE;
                return status;
            }
            if (!canTravel(ai)) { status = Status.NONE; return status; }
            World actual = ai.getPlayer().getWorld();
            int work = maxSamples;
            while (waterY == null && probe < PROBES && work-- > 0 && !ai.getWorkBudget().isExhausted()) {
                int index = probe++;
                int[] offset = NEAR[index % NEAR.length];
                int y = origin.y() - 2 + index / NEAR.length;
                SurfaceRoute.Kind kind = column(actual, origin.x() + offset[0], y, origin.z() + offset[1]);
                if (kind == SurfaceRoute.Kind.UNKNOWN) unknown = true;
                if (kind == SurfaceRoute.Kind.WATER
                        && SurfaceRoute.passable(column(actual, origin.x(), y, origin.z()))) waterY = y;
            }
            if (waterY == null) {
                if (probe >= PROBES) status = Status.NONE;
                return status;
            }
            int[] direction = ai.getWorldModel().leastExploredDirection(world, origin.x() >> 4, origin.z() >> 4);
            double preferred = Math.atan2(direction[1], direction[0]);
            while (sample < SECTORS * RINGS && work-- > 0 && !ai.getWorkBudget().isExhausted()) {
                int index = sample++;
                double angle = preferred + (index % SECTORS) * Math.PI * 2.0 / SECTORS;
                double distance = 12.0 + (index / SECTORS) * 12.0;
                int x = (int) Math.floor(origin.x() + 0.5 + Math.cos(angle) * distance);
                int z = (int) Math.floor(origin.z() + 0.5 + Math.sin(angle) * distance);
                SurfaceRoute.Kind kind = column(actual, x, waterY, z);
                if (kind == SurfaceRoute.Kind.UNKNOWN) unknown = true;
                if (!SurfaceRoute.passable(kind)) continue;
                BlockPoint feet = new BlockPoint(x, waterY + 1, z);
                if (ai.getMemory().contains(MemoryType.UNREACHABLE, world, feet, ai.getTicks())) continue;
                boolean explored = ai.getWorldModel().isExplored(world, x >> 4, z >> 4);
                if (unexploredOnly && explored) continue;
                double score = (explored ? 0 : 128) + (kind == SurfaceRoute.Kind.SHORE ? 24 : 0)
                        + distance * 0.5 + Math.cos(angle - preferred) * 8;
                if (score > bestScore) {
                    bestScore = score;
                    best = new PrimitiveTarget.Point(x + 0.5, waterY + 1.0, z + 0.5);
                }
            }
            if (sample >= SECTORS * RINGS) status = best == null ? Status.NONE : Status.FOUND;
            return status;
        }

        private static double horizontalDistance(BlockPoint a, BlockPoint b) {
            double dx = a.x() - b.x(), dz = a.z() - b.z();
            return dx * dx + dz * dz;
        }
    }
}
