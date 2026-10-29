package me.herry.minecraftAI.ai.learned;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectionalPolicyContextTest {
    @Test void unknownClearAndHitAreDistinctAndEarlierInputsArePreserved() {
        double[] state = new double[PolicyFrame.STATE_SIZE]; state[208] = 1;
        DirectionalPolicyContext.encode(state, 0, true, "OAK_LOG", 8, true);
        assertEquals(1, state[240]); assertEquals(1, state[241]); assertEquals(.5, state[242]);
        assertEquals(1, state[243]); assertEquals(1, state[244 + Math.floorMod("OAK_LOG".hashCode(), 16)]);
        DirectionalPolicyContext.encode(state, 1, true, null, 16, false);
        assertEquals(1, state[260]); assertEquals(0, state[261]); assertEquals(1, state[262]);
        DirectionalPolicyContext.encode(state, 0, false, "OAK_LOG", 8, true);
        for (int i = 240; i < 260; i++) assertEquals(0, state[i]);
        assertEquals(1, state[208]);
        assertThrows(IllegalArgumentException.class, () -> DirectionalPolicyContext.encode(state, 8, true, null, 16, false));
    }
}
