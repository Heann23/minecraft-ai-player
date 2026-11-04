package me.herry.minecraftAI.ai.learned;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectionalPolicyContextTest {
    @Test void exactSightEvidenceDoesNotChangePolicyInputsAndRejectsFalseProvenance() {
        var world = java.util.UUID.randomUUID();
        var rays = new java.util.ArrayList<PolicyFrame.SightRay>();
        var block = new PolicyFrame.BlockEvidence("OAK_LOG", world, 1, 65, 3, 12);
        rays.add(new PolicyFrame.SightRay(0, "HIT", 3, block));
        for (int i = 1; i < 8; i++) rays.add(new PolicyFrame.SightRay(i, "UNLOADED_UNKNOWN", -1, null));
        var sight = new PolicyFrame.SightObservation(world, 12, .5, 65.6, .5, 0, 0, rays);
        double[] state = new double[PolicyFrame.STATE_SIZE];
        DirectionalPolicyContext.encode(state, 0, true, "OAK_LOG", 3, true);
        var options = java.util.List.of(new PolicyFrame.Option("wait:5", new double[PolicyFrame.ACTION_SIZE]));
        var without = new PolicyFrame(world, 12, state, options, false);
        var with = new PolicyFrame(world, 12, state, options, false, null, null, sight);
        assertArrayEquals(without.input(0), with.input(0));
        assertThrows(IllegalArgumentException.class, () -> new PolicyFrame.SightRay(0, "CLEAR", 16, block));
        assertThrows(IllegalArgumentException.class, () -> new PolicyFrame.SightRay(0, "BUDGET_UNKNOWN", 0, null));
        assertThrows(IllegalArgumentException.class, () ->
                new PolicyFrame.SightObservation(world, 13, .5, 65.6, .5, 0, 0, rays));
    }
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
