package me.herry.minecraftAI.ai.observation;

import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.control.OutcomeSignals;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RiskObservationTest {
    private static final UUID WORLD = UUID.fromString("edb98376-eaba-46e4-a385-0b460a6e8a7b");
    private static final UUID OTHER_WORLD = UUID.fromString("3db27a16-9d6c-4ad6-8e31-dda345999a06");
    private static final PrimitiveTarget.Point ORIGIN = new PrimitiveTarget.Point(0.5, 64, 0.5);
    private static final RiskObservation.Exposure UNKNOWN = new RiskObservation.Exposure(
            false, 0, false, 300, 0, 140, 0, 0, true,
            RiskObservation.Presence.UNKNOWN, RiskObservation.Presence.UNKNOWN);

    @Test
    void retainsOnlyBoundedSuffixAndReportsExactlyWhatWasDiscarded() {
        var history = new DamageHistory();
        for (int i = 1; i <= DamageHistory.MAX_EVENTS + 8; i++) {
            history.record(damage(i, WORLD, 1));
        }
        var snapshot = history.snapshot(2, 100, WORLD, UNKNOWN);

        assertEquals(0, snapshot.generation());
        assertEquals(40, snapshot.lastSequence());
        assertEquals(8, snapshot.discardedThroughSequence());
        assertEquals(DamageHistory.MAX_EVENTS, snapshot.damageEvents().size());
        assertEquals(9, snapshot.damageEvents().getFirst().sequence());
        assertEquals(40, snapshot.damageEvents().getLast().sequence());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.damageEvents().clear());
    }

    @Test
    void snapshotOwnsItsEventListInsteadOfTheCallersMutableList() {
        var events = new ArrayList<>(List.of(new RiskObservation.DamageEvent(1, damage(5, WORLD, 2))));
        var snapshot = new RiskObservation(1, 2, 5, WORLD, UNKNOWN, 0, 1, 0, events);
        events.clear();

        assertEquals(1, snapshot.damageEvents().size());
        assertEquals(2, snapshot.damageEvents().getFirst().damage().eventFinalDamage());
    }

    @Test
    void sameTickEventsHaveDistinctOrderAndZeroFinalDamageIsStillAnEvent() {
        var history = new DamageHistory();
        history.record(damage(10, WORLD, 3));
        history.record(damage(10, WORLD, 0));
        var snapshot = history.snapshot(1, 10, WORLD, UNKNOWN);

        assertEquals(List.of(1L, 2L), snapshot.damageEvents().stream()
                .map(RiskObservation.DamageEvent::sequence).toList());
        assertEquals(10, snapshot.damageEvents().getFirst().damage().serverTick());
        assertEquals(10, snapshot.damageEvents().getLast().damage().serverTick());
        assertEquals(0, snapshot.damageEvents().getLast().damage().eventFinalDamage());
        assertEquals(4, snapshot.damageEvents().getLast().damage().eventBaseDamage());
    }

    @Test
    void eventFinalDamageIsIndependentOfNetHealthLossAndDoesNotInferFatality() {
        var history = new DamageHistory();
        history.record(damage(20, WORLD, 8));
        var risk = history.snapshot(5, 21, WORLD, UNKNOWN);
        // Healing, absorption and several events can prevent a per-event attribution.
        var before = body(1, 20);
        var after = body(5, 20);
        var outcome = OutcomeSignals.between(before, after, false);

        assertEquals(8, risk.damageEvents().getFirst().damage().eventFinalDamage());
        assertEquals(0, outcome.healthLost());
        assertFalse(outcome.died());
        assertFalse(outcome.objectiveReached());
    }

    @Test
    void resetStartsNewGenerationWithoutReusingSequenceOrMutatingOldSnapshots() {
        var history = new DamageHistory();
        history.record(damage(30, WORLD, 2));
        var original = history.snapshot(10, 30, WORLD, UNKNOWN);
        history.reset();
        var empty = history.snapshot(10, 30, WORLD, UNKNOWN);
        history.record(damage(31, WORLD, 4));
        var current = history.snapshot(11, 31, WORLD, UNKNOWN);

        assertEquals(1, empty.generation());
        assertTrue(empty.damageEvents().isEmpty());
        assertEquals(1, empty.lastSequence());
        assertEquals(1, empty.discardedThroughSequence());
        assertEquals(1, current.generation(), "first world after reset must not increment again");
        assertEquals(2, current.damageEvents().getFirst().sequence());
        assertEquals(0, original.generation());
        assertEquals(1, original.damageEvents().getFirst().sequence());
        assertEquals(2, original.damageEvents().getFirst().damage().eventFinalDamage());
    }

    @Test
    void worldChangeSeparatesOldEventsWhileKeepingGlobalSequence() {
        var history = new DamageHistory();
        history.record(damage(40, WORLD, 1));
        var changed = history.snapshot(4, 40, OTHER_WORLD, UNKNOWN);
        history.record(damage(41, OTHER_WORLD, 2));
        var current = history.snapshot(4, 41, OTHER_WORLD, UNKNOWN);

        assertEquals(1, changed.generation());
        assertTrue(changed.damageEvents().isEmpty());
        assertEquals(1, changed.discardedThroughSequence());
        assertEquals(2, current.damageEvents().getFirst().sequence());
        assertEquals(OTHER_WORLD, current.damageEvents().getFirst().damage().worldId());
        assertEquals(1, current.generation());
    }

    @Test
    void unsignedServerClockWrapSeparatesGenerationsEvenWhenAiTickDoesNotAdvance() {
        var history = new DamageHistory();
        history.record(damage(0xffff_ffffL, WORLD, 1));
        var beforeWrap = history.snapshot(7, 0xffff_ffffL, WORLD, UNKNOWN);
        var wrapped = history.snapshot(7, 0, WORLD, UNKNOWN);
        history.record(damage(0, WORLD, 2));
        var current = history.snapshot(7, 0, WORLD, UNKNOWN);

        assertEquals(0, beforeWrap.generation());
        assertEquals(1, wrapped.generation());
        assertTrue(wrapped.damageEvents().isEmpty());
        assertEquals(1, wrapped.discardedThroughSequence());
        assertEquals(2, current.damageEvents().getFirst().sequence());
        assertEquals(7, current.aiTick());
        assertEquals(0, current.serverTick());
    }

    @Test
    void olderServerTickOnRecordAlsoStartsNewGeneration() {
        var history = new DamageHistory();
        history.snapshot(10, 100, WORLD, UNKNOWN);
        history.record(damage(99, WORLD, 1));
        var snapshot = history.snapshot(10, 99, WORLD, UNKNOWN);

        assertEquals(1, snapshot.generation());
        assertEquals(1, snapshot.lastSequence());
        assertEquals(99, snapshot.damageEvents().getFirst().damage().serverTick());
    }

    @Test
    void unrefreshedFactsAndHiddenSourcePositionRemainUnknown() {
        var source = new RiskObservation.Source(UUID.randomUUID(), "SKELETON", WORLD, null);
        var history = new DamageHistory();
        history.record(new RiskObservation.Damage(50, WORLD, "PROJECTILE", "minecraft:arrow",
                4, 2, ORIGIN, null, source));
        var snapshot = history.snapshot(2, 50, WORLD, UNKNOWN);

        assertEquals(RiskObservation.Presence.UNKNOWN, snapshot.exposure().suffocation());
        assertEquals(RiskObservation.Presence.UNKNOWN, snapshot.exposure().hostileThreat());
        assertNotEquals(RiskObservation.Presence.ABSENT, snapshot.exposure().hostileThreat());
        assertNull(snapshot.damageEvents().getFirst().damage().directSource());
        assertNull(snapshot.damageEvents().getFirst().damage().causingSource().observedPosition());
    }

    @Test
    void rawExposureKeepsFireCooldownDrowningAirAndDownwardMotionWithoutChoosingAnAction() {
        var exposure = new RiskObservation.Exposure(true, -20, true, -20,
                160, 140, 4.5, -0.8, false,
                RiskObservation.Presence.UNKNOWN, RiskObservation.Presence.UNKNOWN);
        var snapshot = new DamageHistory().snapshot(0, 0, WORLD, exposure);

        assertTrue(snapshot.exposure().inLava());
        assertEquals(-20, snapshot.exposure().fireTicks());
        assertEquals(-20, snapshot.exposure().remainingAir());
        assertEquals(-0.8, snapshot.exposure().verticalVelocity());
        assertEquals(160, snapshot.exposure().freezeTicks());
        assertTrue(snapshot.damageEvents().isEmpty(), "exposure alone must not invent a damage event");
    }

    @Test
    void malformedHistoryCannotHideMissingEventsOrCrossWorldAndFutureEvents() {
        var first = new RiskObservation.DamageEvent(1, damage(5, WORLD, 1));
        var second = new RiskObservation.DamageEvent(2, damage(6, WORLD, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskObservation(1, 0, 6, WORLD, UNKNOWN, 0, 2, 0, List.of(second)));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskObservation(1, 0, 6, WORLD, UNKNOWN, 0, 2, 0, List.of(second, first)));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskObservation(1, 0, 4, WORLD, UNKNOWN, 0, 1, 0, List.of(first)));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskObservation(1, 0, 6, OTHER_WORLD, UNKNOWN, 0, 1, 0, List.of(first)));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskObservation(1, 0, 6, WORLD, UNKNOWN, 0, 1, 2, List.of()));
    }

    @Test
    void invalidAmountsCoordinatesAndClockValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> damage(1, WORLD, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> damage(1, WORLD, -1));
        assertThrows(IllegalArgumentException.class, () -> damage(-1, WORLD, 0));
        assertThrows(IllegalArgumentException.class, () -> damage(0x1_0000_0000L, WORLD, 0));
        assertThrows(IllegalArgumentException.class, () -> new RiskObservation.Source(UUID.randomUUID(),
                "SKELETON", WORLD, new PrimitiveTarget.Point(Double.POSITIVE_INFINITY, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new RiskObservation.Exposure(false, 0, false, 300,
                0, 140, 0, Double.NaN, true,
                RiskObservation.Presence.UNKNOWN, RiskObservation.Presence.UNKNOWN));
    }

    private static RiskObservation.Damage damage(long serverTick, UUID world, double finalDamage) {
        return new RiskObservation.Damage(serverTick, world, "CUSTOM", "UNKNOWN", 4, finalDamage,
                ORIGIN, null, null);
    }

    private static PrimitiveSnapshot body(long tick, double health) {
        return new PrimitiveSnapshot(1, tick, WORLD, ORIGIN, health, 20, 300,
                true, false, false, List.of());
    }
}
