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
    @Test void actualNextObjectiveRemainsBoundToItsTransitionWithoutInventingGoalReward() {
        var memory = new PolicyTaskMemory(); memory.acquire(WORLD, "OAK_LOG", 4, 1);
        var requested = new PolicyFrame(WORLD, 1, new double[PolicyFrame.STATE_SIZE], OPTIONS, false,
                null, memory.observe(WORLD, Map.of()));
        var episode = new TrainingEpisode();
        episode.begin(new PolicyCandidates.Capture(requested, List.of(), body(20), Map.of()), response(), actual(20, Map.of()), 1);
        var next = new PolicyFrame(WORLD, 6, new double[PolicyFrame.STATE_SIZE], OPTIONS, false,
                null, memory.observe(WORLD, Map.of("OAK_LOG", 4)));
        var result = episode.finish(next, body(20), Map.of("OAK_LOG", 4), false, false, 0);
        assertEquals(requested.taskMemory().objective().id(), result.nextTaskMemory().objective().id());
        assertTrue(result.nextTaskMemory().currentlyAchieved());
        assertEquals(0.2005, result.reward(), 1e-9); // Completion is never inferred from the result view alone.
    }
    @Test void explicitCompletionAddsOneWithSeparateAttributionAndActualAdmissionQuantity() {
        var memory = new PolicyTaskMemory(); memory.acquire(WORLD, "OAK_LOG", 4, 1);
        var requested = new PolicyFrame(WORLD, 1, new double[PolicyFrame.STATE_SIZE], OPTIONS, false,
                null, memory.observe(WORLD, Map.of()));
        var admission = memory.observe(WORLD, Map.of("OAK_LOG", 3));
        var next = new PolicyFrame(WORLD, 6, new double[PolicyFrame.STATE_SIZE], OPTIONS, false,
                null, memory.observe(WORLD, Map.of("OAK_LOG", 4)));
        var completion = memory.settleCompletion(admission, next.taskMemory(), true).orElseThrow();
        var episode = new TrainingEpisode();
        episode.begin(new PolicyCandidates.Capture(requested, List.of(), body(20), Map.of()), response(),
                actual(20, Map.of("OAK_LOG", 3)), 2);
        var result = episode.finish(next, body(20), Map.of("OAK_LOG", 4), false, false, 0, List.of(), completion);
        assertEquals("survival-progress/v3", result.rewardContract());
        assertEquals(1.0004, result.reward(), 1e-9);
        assertEquals(0, result.rewardEvidence().objectiveReward());
        assertEquals(3, result.taskCompletion().beforeOwned());
        assertEquals(0, result.frame().taskMemory().owned());
        assertTrue(result.objectiveEvents().isEmpty());
    }
    @Test void onlyConfirmedDisplayedProgressEarnsOnceAndInferenceIsNotRetroactivelyCredited() {
        var rewards = new AdvancementRewards();
        assertEquals(0, rewards.observe("minecraft:recipes/tools/stone_pickaxe", true, false, true));
        assertEquals(0, rewards.observe("minecraft:story/mine_stone", false, true, true));
        assertEquals(1, rewards.observe("minecraft:story/mine_stone", true, true, true));
        assertEquals(0, rewards.observe("minecraft:story/mine_stone", true, true, true));
        assertEquals(0, rewards.observe("minecraft:story/upgrade_tools", true, true, false));
        assertEquals(0, rewards.observe("minecraft:story/upgrade_tools", true, true, true));
        assertThrows(IllegalArgumentException.class, () -> rewards.observe("run a command", true, true, true));
    }

    @Test void actualProgressEventAndRewardVersionRemainBoundToTheFinishedTransition() {
        var episode = new TrainingEpisode();
        episode.begin(new PolicyCandidates.Capture(frame(1), List.of(), body(20), Map.of()), response(), actual(20, Map.of()), 1);
        var events = new java.util.ArrayList<>(List.of("advancement:minecraft:story/mine_stone"));
        var completed = episode.finish(frame(6), body(20), Map.of(), false, false, 1, events);
        events.clear();
        assertEquals(1.0005, completed.reward(), 1e-9);
        assertEquals("survival-progress/v2", completed.rewardContract());
        assertEquals(List.of("advancement:minecraft:story/mine_stone"), completed.objectiveEvents());
    }

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<PolicyFrame.Option> OPTIONS = List.of(new PolicyFrame.Option("chosen", new double[PolicyFrame.ACTION_SIZE]));
    private static SurvivalObservation.Body body(double health) { return new SurvivalObservation.Body(health, 20, 0, 20, 0, 0, 300, 300); }
    private static PolicyFrame frame(long tick) { return new PolicyFrame(WORLD, tick, new double[PolicyFrame.STATE_SIZE], OPTIONS, false); }
    private static PolicyCandidates.Observed actual(double health, Map<String, Integer> stock) {
        var state = new PrimitiveSnapshot(1, 1, WORLD, new PrimitiveTarget.Point(0, 64, 0), health, 20, 300, health > 0,
                false, false, List.of());
        return new PolicyCandidates.Observed(state, body(health), stock, new double[PolicyFrame.STATE_SIZE]);
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
        assertEquals(12, completed.rewardEvidence().healthBefore());
        assertEquals(10, completed.rewardEvidence().healthAfter());
        assertEquals(completed.reward(), completed.rewardEvidence().total(5, false), 1e-9);
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
