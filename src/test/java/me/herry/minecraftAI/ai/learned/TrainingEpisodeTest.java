package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.observation.SurvivalObservation;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Protocol/numerical fixtures, not real Minecraft training evidence. */
class TrainingEpisodeTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<PolicyFrame.Option> OPTIONS = List.of(new PolicyFrame.Option("chosen", new double[PolicyFrame.ACTION_SIZE]));
    private static SurvivalObservation.Body body(double health) { return new SurvivalObservation.Body(health, 20, 0, 20, 0, 0, 300, 300); }
    private static PolicyFrame frame(long tick) { return new PolicyFrame(WORLD, tick, new double[64], OPTIONS, false); }
    private static PolicyCandidates.Observed actual(double health, Map<String, Integer> stock) {
        var state = new PrimitiveSnapshot(1, 1, WORLD, new PrimitiveTarget.Point(0, 64, 0), health, 20, 300, health > 0,
                false, false, List.of());
        return new PolicyCandidates.Observed(state, body(health), stock, new double[64]);
    }
    private static PpoBridge.Response response() { return new PpoBridge.Response(PpoBridge.SCHEMA, UUID.randomUUID().toString(), 0, "chosen", 0, 0, 0, 0); }

    @Test void latencyIsNotInventedAsExecutionAndRewardsUseActualAdmissionBodyState() {
        var episode = new TrainingEpisode();
        var requested = new PolicyCandidates.Capture(frame(10), List.of(), body(20), Map.of());
        episode.begin(requested, response(), actual(12, Map.of()), 20);
        var completed = episode.finish(frame(25), body(10), Map.of(), false, false, 0);
        assertEquals(10, completed.frame().serverTick());
        assertEquals(20, completed.appliedTick());
        assertEquals(5, completed.elapsedTicks());
        assertEquals(-0.0995, completed.reward(), 1e-9);
        assertFalse(episode.active());
    }

    @Test void deathOnTheAdmissionTickIsRecordedWithZeroActualTicksRatherThanDroppedOrRelabeled() {
        var episode = new TrainingEpisode();
        episode.begin(new PolicyCandidates.Capture(frame(10), List.of(), body(4), Map.of()), response(), actual(4, Map.of()), 10);
        var ended = episode.finish(frame(10), body(0), Map.of(), true, true, 0);
        assertEquals(0, ended.elapsedTicks());
        assertTrue(ended.terminated());
        assertTrue(ended.reward() < -10);
    }

    @Test void startingInventoryAndRepeatedCollectionDoNotBecomeRepeatedDiscoveryRewards() {
        var episode = new TrainingEpisode();
        episode.begin(new PolicyCandidates.Capture(frame(1), List.of(), body(20), Map.of("DIRT", 1)), response(), actual(20, Map.of("DIRT", 1)), 1);
        var first = episode.finish(frame(6), body(20), Map.of("DIRT", 2, "WHEAT", 1), false, false, 0);
        assertEquals(0.2005, first.reward(), 1e-9);
        episode.begin(new PolicyCandidates.Capture(frame(6), List.of(), body(20), Map.of()), response(), actual(20, Map.of("WHEAT", 1)), 6);
        var repeated = episode.finish(frame(11), body(20), Map.of("WHEAT", 2), false, false, 0);
        assertEquals(0.0005, repeated.reward(), 1e-9);
        assertEquals(first.sequence() + 1, repeated.sequence());
        assertEquals(first.session(), repeated.session());
    }
}
