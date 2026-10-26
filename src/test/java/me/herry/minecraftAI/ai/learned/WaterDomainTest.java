package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Observation-domain contracts, not actual water entry/exit or swimming acceptance. */
class WaterDomainTest {
    @Test void shoreInputsRequireCurrentPositiveWaterEvidenceRatherThanAnUnknownCell() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        assertFalse(WaterDomain.observed(state));
        assertFalse(WaterDomain.knownDry(state));
        state[66] = 1;
        assertFalse(WaterDomain.observed(state));
        state[64] = 1;
        assertTrue(WaterDomain.observed(state));
        assertFalse(WaterDomain.knownDry(state));
        // A solid waterlogged cell is still observed fluid; the executor does not choose how to approach it.
        state[65] = 1;
        assertTrue(WaterDomain.observed(state));
    }
    @Test void bodilyImmersionDoesNotNeedACompleteTerrainScanButDryAbsenceDoes() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        state[7] = 1;
        assertTrue(WaterDomain.observed(state));
        state[7] = 0;
        for (int cell = 0; cell < 12; cell++) state[64 + cell * 4] = 1;
        assertTrue(WaterDomain.knownDry(state));
        state[64 + 11 * 4] = 0;
        assertFalse(WaterDomain.knownDry(state));
        assertFalse(WaterDomain.observed(state));
    }
}
