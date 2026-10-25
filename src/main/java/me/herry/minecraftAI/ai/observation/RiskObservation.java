package me.herry.minecraftAI.ai.observation;

import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, in-memory observations of exposure and accepted damage events.
 * This contract is independent of Observation/Experience and PrimitiveSnapshot version 1.
 * Server ticks use the unsigned 32-bit Bukkit clock; aiTick counts only running AI ticks.
 * Events are the last bounded events in this generation, not a time-window damage total.
 */
public record RiskObservation(int schemaVersion, long aiTick, long serverTick, UUID worldId,
                              Exposure exposure, long generation, long lastSequence,
                              long discardedThroughSequence, List<DamageEvent> damageEvents) {
    public static final int SCHEMA_VERSION = 1;
    private static final long MAX_SERVER_TICK = 0xffff_ffffL;

    public RiskObservation {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported risk schema");
        nonnegative(aiTick, "aiTick");
        checkServerTick(serverTick);
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(exposure, "exposure");
        nonnegative(generation, "generation");
        nonnegative(lastSequence, "lastSequence");
        nonnegative(discardedThroughSequence, "discardedThroughSequence");
        if (discardedThroughSequence > lastSequence) {
            throw new IllegalArgumentException("discarded sequence exceeds last sequence");
        }
        damageEvents = List.copyOf(Objects.requireNonNull(damageEvents, "damageEvents"));
        if (damageEvents.size() > DamageHistory.MAX_EVENTS) {
            throw new IllegalArgumentException("too many retained damage events");
        }
        if (lastSequence - discardedThroughSequence != damageEvents.size()) {
            throw new IllegalArgumentException("retained events must account for the sequence suffix");
        }
        long previousSequence = discardedThroughSequence;
        long previousTick = -1;
        for (DamageEvent event : damageEvents) {
            if (event.sequence() != previousSequence + 1) {
                throw new IllegalArgumentException("damage events must have consecutive sequence numbers");
            }
            Damage damage = event.damage();
            if (!worldId.equals(damage.worldId())) {
                throw new IllegalArgumentException("damage event belongs to another world");
            }
            if (damage.serverTick() > serverTick || damage.serverTick() < previousTick) {
                throw new IllegalArgumentException("damage event ticks must be ordered and not in the future");
            }
            previousSequence = event.sequence();
            previousTick = damage.serverTick();
        }
    }

    public enum Presence {
        PRESENT, ABSENT, UNKNOWN
    }

    /**
     * Raw exposure is not predicted injury, safety, or a preferred action.
     * Cached facts that were not refreshed must remain UNKNOWN.
     * Fire cooldown ticks, remaining air and vertical velocity may be negative during normal gameplay.
     */
    public record Exposure(boolean inLava, int fireTicks, boolean inWater, int remainingAir,
                           int freezeTicks, int maxFreezeTicks, double fallDistance,
                           double verticalVelocity, boolean grounded, Presence suffocation,
                           Presence hostileThreat) {
        public Exposure {
            nonnegative(freezeTicks, "freezeTicks");
            nonnegative(maxFreezeTicks, "maxFreezeTicks");
            finiteNonnegative(fallDistance, "fallDistance");
            finite(verticalVelocity, "verticalVelocity");
            Objects.requireNonNull(suffocation, "suffocation");
            Objects.requireNonNull(hostileThreat, "hostileThreat");
        }
    }

    /**
     * UUIDs identify internal event participants, not model features or public identities.
     * Position is absent unless observed; it is not the actual collision/knockback direction.
     */
    public record Source(UUID id, String type, UUID worldId,
                         @Nullable PrimitiveTarget.Point observedPosition) {
        public Source {
            Objects.requireNonNull(id, "id");
            type = text(type, "source type", 96);
            Objects.requireNonNull(worldId, "worldId");
            if (observedPosition != null) point(observedPosition);
        }
    }

    /**
     * One uncancelled MONITOR event, including events with zero final damage.
     * eventFinalDamage is the event's final calculation, not measured health/absorption loss.
     * No per-event actual health loss, fatality, or collision direction is inferred here.
     */
    public record Damage(long serverTick, UUID worldId, String cause, String damageTypeKey,
                         double eventBaseDamage, double eventFinalDamage,
                         PrimitiveTarget.Point victimPosition, @Nullable Source directSource,
                         @Nullable Source causingSource) {
        public Damage {
            checkServerTick(serverTick);
            Objects.requireNonNull(worldId, "worldId");
            cause = text(cause, "cause", 96);
            damageTypeKey = text(damageTypeKey, "damageTypeKey", 128);
            finiteNonnegative(eventBaseDamage, "eventBaseDamage");
            finiteNonnegative(eventFinalDamage, "eventFinalDamage");
            point(Objects.requireNonNull(victimPosition, "victimPosition"));
        }
    }

    /** Sequence distinguishes multiple events at the same server tick. */
    public record DamageEvent(long sequence, Damage damage) {
        public DamageEvent {
            if (sequence <= 0) throw new IllegalArgumentException("damage sequence must be positive");
            Objects.requireNonNull(damage, "damage");
        }
    }

    static void checkServerTick(long tick) {
        if (tick < 0 || tick > MAX_SERVER_TICK) {
            throw new IllegalArgumentException("serverTick must use the unsigned 32-bit server clock");
        }
    }

    private static void nonnegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be nonnegative");
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static void finiteNonnegative(double value, String name) {
        finite(value, name);
        if (value < 0) throw new IllegalArgumentException(name + " must be nonnegative");
    }

    private static void point(PrimitiveTarget.Point point) {
        finite(point.x(), "position x");
        finite(point.y(), "position y");
        finite(point.z(), "position z");
    }

    private static String text(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded, nonblank text without control characters");
        }
        return value;
    }
}
