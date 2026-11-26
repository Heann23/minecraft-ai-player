package me.herry.minecraftAI.ai.learned;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldPolicyContextTest {
    @Test
    void encodesOverworldAltitudeLightRespawnAndWorkstations() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        WorldPolicyContext.encode(state, World.Environment.NORMAL, 64, 9, 15,
                true, true, false, false, 10, 20, 30, 0.5, 0, 0);

        assertEquals(1.0, state[432]);
        assertEquals(0.0, state[433]);
        assertEquals(0.0, state[434]);
        assertEquals((64.0 + 64.0) / 384.0, state[435], 1e-9);
        assertEquals(9.0 / 15.0, state[436], 1e-9);
        assertEquals(1.0, state[437], 1e-9);
        assertEquals(1.0, state[438]);
        assertEquals(1.0, state[439]);
        assertEquals(0.0, state[440]);
        for (int i = 441; i <= 447; i++) {
            assertEquals(0.0, state[i]);
        }
    }

    @Test
    void encodesNetherAndEndDragonCrystalFactsWithClamping() {
        double[] nether = new double[PolicyFrame.STATE_SIZE];
        WorldPolicyContext.encode(nether, World.Environment.NETHER, 32, 12, 0,
                false, false, true, false, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, nether[432]);
        assertEquals(1.0, nether[433]);
        assertEquals(0.0, nether[434]);
        assertEquals(1.0, nether[440]);

        double[] end = new double[PolicyFrame.STATE_SIZE];
        WorldPolicyContext.encode(end, World.Environment.THE_END, 60, 0, 0,
                false, false, false, true, -64.0, 32.0, 256.0, 0.75, 6, 48.0);
        assertEquals(0.0, end[432]);
        assertEquals(0.0, end[433]);
        assertEquals(1.0, end[434]);
        assertEquals(1.0, end[441]);
        assertEquals(-0.5, end[442], 1e-9);
        assertEquals(0.25, end[443], 1e-9);
        assertEquals(1.0, end[444], 1e-9);
        assertEquals(0.75, end[445], 1e-9);
        assertEquals(0.5, end[446], 1e-9);
        assertEquals(0.5, end[447], 1e-9);
    }

    @Test
    void rejectsWrongStateVectorLength() {
        assertThrows(IllegalArgumentException.class, () ->
                WorldPolicyContext.encode(new double[432], World.Environment.NORMAL, 64, 0, 15,
                        false, false, false, false, 0, 0, 0, 0, 0, 0));
    }
}
