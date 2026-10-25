package me.herry.minecraftAI.ai.observation;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.Nullable;

/** Copies observed damage and current body exposure; does not choose or execute a response. */
public final class RiskCapture {
    private static final double MAX_SOURCE_DISTANCE_SQUARED = 32.0 * 32.0;

    private RiskCapture() {
    }

    /** Server ticks advance even when this AI is stopped. History handles unsigned clock rollover. */
    private static long serverTick() {
        return Integer.toUnsignedLong(Bukkit.getCurrentTick());
    }

    public static RiskObservation capture(AIPlayer ai, DamageHistory history) {
        requireMainThread();
        Player player = ai.getPlayer();
        var exposure = new RiskObservation.Exposure(player.isInLava(), player.getFireTicks(), player.isInWater(),
                player.getRemainingAir(), player.getFreezeTicks(), player.getMaxFreezeTicks(),
                player.getFallDistance(), player.getVelocity().getY(), ai.getBody().isGrounded(),
                RiskObservation.Presence.UNKNOWN, RiskObservation.Presence.UNKNOWN);
        // Suffocation and hostile perception are cached without an observation timestamp. Do not relabel them
        // as fresh absence; connecting those observations is a separate part of P0-01.
        return history.snapshot(ai.getTicks(), serverTick(), player.getWorld().getUID(), exposure);
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
