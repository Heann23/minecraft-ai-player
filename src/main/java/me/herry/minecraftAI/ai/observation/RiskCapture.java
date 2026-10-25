package me.herry.minecraftAI.ai.observation;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.Nullable;

/** Copies observed damage and current body exposure; does not choose or execute a response. */
public final class RiskCapture {
    private static final double MAX_SOURCE_DISTANCE_SQUARED = 32.0 * 32.0;
    private static final int MAX_HOSTILE_CANDIDATES = 32;

    private RiskCapture() {
    }

    /** Server ticks advance even when this AI is stopped. History handles unsigned clock rollover. */
    private static long serverTick() {
        return Integer.toUnsignedLong(Bukkit.getCurrentTick());
    }

    public static RiskObservation capture(AIPlayer ai, DamageHistory history) {
        requireMainThread();
        Player player = ai.getPlayer();
        long capturedTick = serverTick();
        var exposure = new RiskObservation.Exposure(player.isInLava(), player.getFireTicks(), player.isInWater(),
                player.getRemainingAir(), player.getFreezeTicks(), player.getMaxFreezeTicks(),
                player.getFallDistance(), player.getVelocity().getY(), ai.getBody().isGrounded(),
                currentSuffocation(player), currentHostileThreat(ai, capturedTick));
        return history.snapshot(ai.getTicks(), capturedTick, player.getWorld().getUID(), exposure);
    }

    /** Current two-cell occlusion, matching the existing perception predicate; not proof of applied suffocation damage. */
    private static RiskObservation.Presence currentSuffocation(Player player) {
        if (player.isSleeping()) return RiskObservation.Presence.ABSENT;
        World world = player.getWorld();
        BlockPoint feet = Positions.feet(player.getLocation());
        return occupancyPresence(false, occluding(world, feet), occluding(world, feet.offset(0, 1, 0)));
    }

    private static @Nullable Boolean occluding(World world, BlockPoint cell) {
        if (cell.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight()) return false;
        if (!Positions.isLoaded(world, cell)) return null;
        return PerceptionSystem.isBurying(Positions.block(world, cell).getType());
    }

    static RiskObservation.Presence occupancyPresence(boolean sleeping, @Nullable Boolean feet, @Nullable Boolean head) {
        if (sleeping) return RiskObservation.Presence.ABSENT;
        if (Boolean.TRUE.equals(feet) || Boolean.TRUE.equals(head)) return RiskObservation.Presence.PRESENT;
        return feet == null || head == null ? RiskObservation.Presence.UNKNOWN : RiskObservation.Presence.ABSENT;
    }

    /** Presence is scoped to visible candidates in the completed scan cube. Old/partial caches never assert absence. */
    private static RiskObservation.Presence currentHostileThreat(AIPlayer ai, long now) {
        Player player = ai.getPlayer();
        Location at = player.getLocation();
        Perception perception = ai.getPerception();
        var stamp = perception.getEntityScanStamp();
        if (!freshEntityScan(stamp, player.getWorld().getUID(), now, point(at))) return RiskObservation.Presence.UNKNOWN;
        boolean complete = true;
        int inspected = 0;
        for (LivingEntity hostile : perception.getHostiles()) {
            if (++inspected > MAX_HOSTILE_CANDIDATES || ai.getWorkBudget().isExhausted()) { complete = false; break; }
            if (!hostile.isValid() || hostile.isDead() || !hostile.getWorld().equals(player.getWorld())) continue;
            Location source = hostile.getLocation();
            if (Math.abs(source.getX() - stamp.x()) > stamp.range() || Math.abs(source.getY() - stamp.y()) > stamp.range()
                    || Math.abs(source.getZ() - stamp.z()) > stamp.range()) { complete = false; continue; }
            if (hostile.isInvisible() || !PerceptionSystem.canSee(player, hostile)) { complete = false; continue; }
            return hostilePresence(true, true, complete);
        }
        return hostilePresence(true, false, complete);
    }

    static boolean freshEntityScan(@Nullable Perception.EntityScanStamp stamp, java.util.UUID world, long now,
                                   PrimitiveTarget.Point at) {
        return stamp != null && stamp.complete() && stamp.worldId().equals(world) && stamp.serverTick() == now
                && Double.compare(stamp.x(), at.x()) == 0 && Double.compare(stamp.y(), at.y()) == 0
                && Double.compare(stamp.z(), at.z()) == 0;
    }

    static RiskObservation.Presence hostilePresence(boolean fresh, boolean seen, boolean complete) {
        if (!fresh) return RiskObservation.Presence.UNKNOWN;
        if (seen) return RiskObservation.Presence.PRESENT;
        return complete ? RiskObservation.Presence.ABSENT : RiskObservation.Presence.UNKNOWN;
    }

    /** Called only for a non-cancelled MONITOR event, before its damage is necessarily applied to health. */
    public static void record(AIPlayer ai, EntityDamageEvent event) {
        requireMainThread();
        Player player = ai.getPlayer();
        var source = event.getDamageSource();
        Entity directEntity = source.getDirectEntity();
        Entity causingEntity = source.getCausingEntity();
        RiskObservation.Source direct = source(ai, directEntity);
        RiskObservation.Source causing = directEntity != null && causingEntity != null
                && directEntity.getUniqueId().equals(causingEntity.getUniqueId())
                ? direct : source(ai, causingEntity);
        ai.observeDamage(new RiskObservation.Damage(serverTick(), player.getWorld().getUID(),
                event.getCause().name(), source.getDamageType().getKey().toString(),
                event.getDamage(), event.getFinalDamage(), point(player.getLocation()), direct, causing));
    }

    /** An observable source position is not the impact direction, projectile velocity or knockback vector. */
    private static @Nullable RiskObservation.Source source(AIPlayer ai, @Nullable Entity entity) {
        if (entity == null) return null;
        Player player = ai.getPlayer();
        PrimitiveTarget.Point observedPosition = null;
        if (entity.isValid() && entity.getWorld().equals(player.getWorld())
                && !(entity instanceof LivingEntity living && living.isInvisible())
                && !ai.getWorkBudget().isExhausted()) {
            Location position = entity.getLocation();
            if (position.distanceSquared(player.getLocation()) <= MAX_SOURCE_DISTANCE_SQUARED
                    && PerceptionSystem.canSee(player, entity)) {
                observedPosition = point(position);
            }
        }
        return new RiskObservation.Source(entity.getUniqueId(), entity.getType().name(),
                entity.getWorld().getUID(), observedPosition);
    }

    private static PrimitiveTarget.Point point(Location location) {
        return new PrimitiveTarget.Point(location.getX(), location.getY(), location.getZ());
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("risk capture requires the server main thread");
    }
}
