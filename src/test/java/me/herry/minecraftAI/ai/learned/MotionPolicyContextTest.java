package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MotionPolicyContextTest {
    @Test void motionFactsPreserveOldObservationAndDistinguishDirectionAndPoseWithoutChoosingKeys() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        java.util.Arrays.fill(state, 0, 192, 0.25);
        MotionPolicyContext.encode(state, 90, -2, 1, new boolean[]{true, true, false, false, false, false, false, true},
                new double[]{0.6, 0.6, 0.4}, 3);
        for (int i = 0; i < 192; i++) assertEquals(0.25, state[i]);
        assertEquals(1, state[192], 1e-12); assertEquals(0, state[193], 1e-12);
        assertEquals(-0.5, state[194]); assertEquals(0.25, state[195]);
        assertEquals(1, state[196]); assertEquals(1, state[197]); assertEquals(0, state[198]);
        assertEquals(1, state[203]); assertEquals(0.3, state[204]); assertEquals(0.75, state[207]);
    }
}
