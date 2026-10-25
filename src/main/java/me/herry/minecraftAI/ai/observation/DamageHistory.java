package me.herry.minecraftAI.ai.observation;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-thread-confined, bounded in-memory history. It retains at most the last 32
 * accepted events in the current generation; "recent" does not imply a time TTL.
 * No world/entity references, disk writes, or autonomous training collection are used.
 */
public final class DamageHistory {
    public static final int MAX_EVENTS = 32;

    private final ArrayDeque<RiskObservation.DamageEvent> events = new ArrayDeque<>();
    private UUID worldId;
    private long lastServerTick = -1;
    private long generation;
    private long lastSequence;
    private long discardedThroughSequence;

    public void record(RiskObservation.Damage damage) {
        Objects.requireNonNull(damage, "damage");
        align(damage.worldId(), damage.serverTick());
        long sequence = Math.incrementExact(lastSequence);
        events.addLast(new RiskObservation.DamageEvent(sequence, damage));
        lastSequence = sequence;
        if (events.size() > MAX_EVENTS) {
            discardedThroughSequence = events.removeFirst().sequence();
        }
    }

    public RiskObservation snapshot(long aiTick, long serverTick, UUID worldId,
                                    RiskObservation.Exposure exposure) {
        if (aiTick < 0) throw new IllegalArgumentException("aiTick must be nonnegative");
        RiskObservation.checkServerTick(serverTick);
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(exposure, "exposure");
        align(worldId, serverTick);
        return new RiskObservation(RiskObservation.SCHEMA_VERSION, aiTick, serverTick, worldId,
                exposure, generation, lastSequence, discardedThroughSequence, List.copyOf(events));
    }

    /** Called on a lifecycle boundary; sequence identity is never reused. */
    public void reset() {
        clearGeneration();
        worldId = null;
        lastServerTick = -1;
    }

    private void align(UUID observedWorld, long serverTick) {
        // The first world in a new history/reset does not require another generation.
        if (worldId != null && (!worldId.equals(observedWorld) || serverTick < lastServerTick)) {
            // Includes unsigned Bukkit clock wrap from 0xffffffff to zero.
            clearGeneration();
        }
        worldId = observedWorld;
        lastServerTick = serverTick;
    }

    private void clearGeneration() {
        generation = Math.incrementExact(generation);
        events.clear();
        discardedThroughSequence = lastSequence;
    }
}
