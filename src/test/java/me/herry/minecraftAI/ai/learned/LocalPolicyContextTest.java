package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalPolicyContextTest {
    @Test void betweenScanTicksRevalidatedPositiveSightingsSurviveButAbsenceRemainsUnknown() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        LocalPolicyContext.entityContext(state, false, () -> {
            state[113] = 1 / 16.0;
            state[114] = 2 / 32.0;
            return true;
        }, () -> true);
        assertEquals(1 / 16.0, state[113]);
        assertEquals(2 / 32.0, state[114]);
        assertEquals(0, state[112]);
        LocalPolicyContext.entityContext(state, true, () -> true, () -> false);
        assertEquals(0, state[112]);
        LocalPolicyContext.entityContext(state, true, () -> true, () -> true);
        assertEquals(1, state[112]);
    }

    @Test void hiddenOrUnloadedCellsRemainUnknownRatherThanClearOrSafe() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        LocalPolicyContext.encodeCell(state, 0, false, true, true, true);
        assertArrayEquals(new double[4], java.util.Arrays.copyOfRange(state, 64, 68));
        LocalPolicyContext.encodeCell(state, 1, true, false, true, false);
        assertArrayEquals(new double[]{1, 0, 1, 0}, java.util.Arrays.copyOfRange(state, 68, 72));
        LocalPolicyContext.encodeCell(state, 2, true, true, false, false);
        assertArrayEquals(new double[]{1, 1, 0, 0}, java.util.Arrays.copyOfRange(state, 72, 76));
        assertEquals(12, LocalPolicyContext.cells(new BlockPoint(0, 64, 0)).stream().distinct().count());
    }

    @Test void stalePartialOrDisplacedEntityScansDoNotBecomeCurrentAbsence() {
        UUID world = UUID.randomUUID();
        var scan = new Perception.EntityScanStamp(world, 10, 32, true, 1, 64, 2);
        assertTrue(LocalPolicyContext.fresh(scan, world, 10, 1, 64, 2));
        assertFalse(LocalPolicyContext.fresh(scan, world, 11, 1, 64, 2));
        assertFalse(LocalPolicyContext.fresh(scan, world, 10, 1.01, 64, 2));
        assertFalse(LocalPolicyContext.fresh(scan, UUID.randomUUID(), 10, 1, 64, 2));
        assertFalse(LocalPolicyContext.fresh(new Perception.EntityScanStamp(world, 10, 32, false, 1, 64, 2), world, 10, 1, 64, 2));
    }
}
