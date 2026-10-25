package me.herry.minecraftAI.ai.observation;

import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RiskCaptureFreshnessTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final PrimitiveTarget.Point HERE = new PrimitiveTarget.Point(1.5, 64, -2.5);

    @Test void bothBodyCellsMustBeObservedBeforeClaimingNoOcclusion() {
        assertEquals(RiskObservation.Presence.ABSENT, RiskCapture.occupancyPresence(false, false, false));
        assertEquals(RiskObservation.Presence.UNKNOWN, RiskCapture.occupancyPresence(false, false, null));
        assertEquals(RiskObservation.Presence.UNKNOWN, RiskCapture.occupancyPresence(false, null, null));
        assertEquals(RiskObservation.Presence.PRESENT, RiskCapture.occupancyPresence(false, null, true));
        assertEquals(RiskObservation.Presence.PRESENT, RiskCapture.occupancyPresence(false, true, false));
        assertEquals(RiskObservation.Presence.ABSENT, RiskCapture.occupancyPresence(true, true, true));
    }

    @Test void cacheTimeWorldPositionAndCompletionAreIndependentFreshnessConditions() {
        var stamp = new Perception.EntityScanStamp(WORLD, 20, 32, true, HERE.x(), HERE.y(), HERE.z());
        assertTrue(RiskCapture.freshEntityScan(stamp, WORLD, 20, HERE));
        assertFalse(RiskCapture.freshEntityScan(stamp, WORLD, 21, HERE));
        assertFalse(RiskCapture.freshEntityScan(stamp, UUID.randomUUID(), 20, HERE));
        assertFalse(RiskCapture.freshEntityScan(stamp, WORLD, 20, new PrimitiveTarget.Point(1.5, 64, -2.4)));
        assertFalse(RiskCapture.freshEntityScan(null, WORLD, 20, HERE));
        var partial = new Perception.EntityScanStamp(WORLD, 20, 32, false, HERE.x(), HERE.y(), HERE.z());
        assertFalse(RiskCapture.freshEntityScan(partial, WORLD, 20, HERE));
    }

    @Test void hiddenOrBudgetTruncatedCandidatesDoNotBecomeAbsenceAndPositiveEvidenceCanRemainKnown() {
        assertEquals(RiskObservation.Presence.UNKNOWN, RiskCapture.hostilePresence(false, true, true));
        assertEquals(RiskObservation.Presence.UNKNOWN, RiskCapture.hostilePresence(true, false, false));
        assertEquals(RiskObservation.Presence.ABSENT, RiskCapture.hostilePresence(true, false, true));
        assertEquals(RiskObservation.Presence.PRESENT, RiskCapture.hostilePresence(true, true, false));
    }

    @Test void unsignedClockRolloverCannotReuseAnOldScan() {
        var stamp = new Perception.EntityScanStamp(WORLD, 0xffff_ffffL, 32, true, HERE.x(), HERE.y(), HERE.z());
        assertFalse(RiskCapture.freshEntityScan(stamp, WORLD, 0, HERE));
        assertEquals(32, stamp.range());
        assertEquals(0xffff_ffffL, stamp.serverTick());
        assertThrows(IllegalArgumentException.class,
                () -> new Perception.EntityScanStamp(WORLD, -1, 32, true, 0, 0, 0));
    }
}
