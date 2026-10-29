package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Discovery coverage under a shared tick budget, not learned behavior or Minecraft capability acceptance. */
class CandidateDiscoveryTest {
    @Test void consumptionAbsenceRequiresCoverageAndNeverChangesPolicyInputs() {
        var partial = new PolicyFrame.ConsumptionScan(36, 3, 1, 1, 1, 0, 0, 0, 0);
        assertFalse(partial.complete());
        assertTrue(new PolicyFrame.ConsumptionScan(36, 36, 36, 0, 0, 0, 0, 0, 0).complete());
        assertThrows(IllegalArgumentException.class, () -> new PolicyFrame.ConsumptionScan(36, 3, 0, 0, 0, 0, 0, 0, 0));
        var options = java.util.List.of(new PolicyFrame.Option("wait", new double[PolicyFrame.ACTION_SIZE]));
        var world = java.util.UUID.randomUUID();
        var original = new PolicyFrame(world, 1, new double[PolicyFrame.STATE_SIZE], options, false);
        var evidence = new PolicyFrame.Discovery("candidate-discovery/v1", java.util.List.of("CONSUME"),
                java.util.Map.of("CONSUME", 0), true, -1, -1, false, partial);
        var annotated = new PolicyFrame(world, 1, original.state(), options, false, evidence);
        assertArrayEquals(original.input(0), annotated.input(0));
    }
    @Test void aSourceThatUsesTheRemainingBudgetCannotPermanentlyHideOtherSources() {
        var discovery = new CandidateDiscovery();
        var visited = EnumSet.noneOf(CandidateDiscovery.Source.class);
        int total = CandidateDiscovery.Source.values().length;
        for (int capture = 0; capture < total; capture++) {
            var exhausted = new AtomicBoolean();
            var visit = discovery.visit(exhausted::get, source -> {
                assertTrue(visited.add(source), "a source was repeated before every source had a turn");
                exhausted.set(true);
            });
            assertEquals(1, visit.sources().size());
            assertTrue(visit.budgetExhausted());
        }
        assertEquals(EnumSet.allOf(CandidateDiscovery.Source.class), visited);
    }

    @Test void anAlreadyExhaustedTickDoesNotPretendToInspectOrSkipASource() {
        var discovery = new CandidateDiscovery();
        var empty = discovery.visit(() -> true, source -> fail("no budget for world inspection"));
        assertTrue(empty.sources().isEmpty());
        var admitted = new ArrayList<CandidateDiscovery.Source>();
        discovery.visit(() -> !admitted.isEmpty(), admitted::add);
        assertEquals(CandidateDiscovery.Source.DIRECT_MOVE, admitted.getFirst());
    }

    @Test void fullCapturesStillRotateTheirFirstSourceAndVisitEachSourceOnlyOnce() {
        var discovery = new CandidateDiscovery();
        var firsts = EnumSet.noneOf(CandidateDiscovery.Source.class);
        for (int capture = 0; capture < CandidateDiscovery.Source.values().length; capture++) {
            var visit = discovery.visit(() -> false, source -> {});
            assertFalse(visit.budgetExhausted());
            assertEquals(EnumSet.allOf(CandidateDiscovery.Source.class), EnumSet.copyOf(visit.sources()));
            assertEquals(CandidateDiscovery.Source.values().length, visit.sources().size());
            assertTrue(firsts.add(visit.sources().getFirst()));
        }
    }
}
