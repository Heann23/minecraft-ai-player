package me.herry.minecraftAI.ai.learned;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PolicyWeightsTest {
    @TempDir Path directory;

    /** Synthetic numerical fixture only; never a model trained or deployed in Minecraft. */
    private Path fixture(long steps, String environment, double weight) throws Exception {
        double[][] hidden = new double[1][PolicyFrame.INPUT_SIZE];
        hidden[0][PolicyFrame.STATE_SIZE] = weight;
        var artifact = new PolicyWeights.Artifact(PolicyWeights.SCHEMA, PolicyFrame.CONTRACT, "ppo", environment,
                steps, "numerical-unit-fixture-not-training", "0".repeat(64), hidden, new double[]{0}, new double[]{1}, 0);
        Path file = directory.resolve("actor-" + steps + "-" + weight + "-" + environment + ".json");
        Files.writeString(file, new Gson().toJson(artifact));
        return file;
    }

    private PolicyFrame frame() {
        double[] left = new double[PolicyFrame.ACTION_SIZE]; left[0] = -1;
        double[] right = new double[PolicyFrame.ACTION_SIZE]; right[0] = 1;
        return new PolicyFrame(UUID.randomUUID(), 5, new double[PolicyFrame.STATE_SIZE],
                List.of(new PolicyFrame.Option("left", left), new PolicyFrame.Option("right", right)), false);
    }

    @Test void swappingTheLearnedParameterChangesTheSelectedActionWithoutAnyGoalOrCostRule() throws Exception {
        var positive = PolicyWeights.load(fixture(1, "minecraft-paper", 2)).predict(frame());
        var negative = PolicyWeights.load(fixture(1, "minecraft-paper", -2)).predict(frame());
        assertEquals("right", positive.candidate());
        assertEquals("left", negative.candidate());
        assertTrue(Double.isFinite(positive.logProbability()));
        assertTrue(positive.logProbability() <= 0);
        assertNotEquals(positive.modelSha256(), negative.modelSha256());
    }

    @Test void initializationOrAnotherEnvironmentCannotMasqueradeAsATrainedPaperPolicy() throws Exception {
        Path untrained = fixture(0, "minecraft-paper", 1);
        Path synthetic = fixture(10, "toy-simulator", 1);
        assertThrows(java.io.IOException.class, () -> PolicyWeights.load(untrained));
        assertThrows(java.io.IOException.class, () -> PolicyWeights.load(synthetic));
        assertThrows(java.io.IOException.class, () -> PolicyWeights.load(directory.resolve("missing.json")));
        Path malformed = directory.resolve("malformed.json"); Files.writeString(malformed, "{}");
        assertThrows(java.io.IOException.class, () -> PolicyWeights.load(malformed));
    }

    @Test void categoricalExecutionUsesTheLearnedProbabilitiesAndReportsTheChosenLogProbability() throws Exception {
        var policy = PolicyWeights.load(fixture(1, "minecraft-paper", 2));
        var observed = frame();
        var random = new java.util.Random(741);
        int right = 0;
        double margin = 2 * Math.tanh(2);
        double expectedRight = 1 / (1 + Math.exp(-margin));
        for (int i = 0; i < 2000; i++) {
            var selected = policy.sample(observed, random);
            boolean isRight = selected.candidate().equals("right");
            if (isRight) right++;
            assertEquals(Math.log(isRight ? expectedRight : 1 - expectedRight), selected.logProbability(), 1e-12);
        }
        assertEquals(expectedRight, right / 2000.0, 0.04);
        assertTrue(right < 2000, "categorical execution must not silently become greedy argmax");
    }

    @Test void observationsAndCandidateFeaturesCannotBeChangedAfterSelectionInputWasCaptured() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        double[] action = new double[PolicyFrame.ACTION_SIZE]; action[0] = 1;
        var option = new PolicyFrame.Option("action", action);
        var frame = new PolicyFrame(UUID.randomUUID(), 1, state, List.of(option), true);
        state[0] = 9; action[0] = 9; frame.state()[0] = 9; option.features()[0] = 9;
        assertEquals(0, frame.input(0)[0]);
        assertEquals(1, frame.input(0)[PolicyFrame.STATE_SIZE]);
        assertThrows(IllegalArgumentException.class, () -> new PolicyFrame(UUID.randomUUID(), 1,
                new double[1], List.of(option), true));
    }

    @Test void retainingAnInputAlreadyEnumeratedDoesNotDuplicateTheCandidateOrInvalidateTheFrame() {
        var raw = new PolicyCandidates.Candidate("wait:5",
                new me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.Wait(5), null, null,
                new PolicyFrame.Option("wait:5", new double[PolicyFrame.ACTION_SIZE]));
        var generated = raw.committed(20);
        double[] retainedFeatures = generated.encoded().features(); retainedFeatures[55] = 1;
        var retained = new PolicyCandidates.Candidate(generated.id(), generated.command(), null, null,
                new PolicyFrame.Option(generated.id(), retainedFeatures), 20, generated.inputId());
        var choices = PolicyCandidates.selectFair(List.of(retained, raw.committed(5), generated, raw.committed(80)), retained.id());
        assertEquals(3, choices.size());
        assertSame(retained, choices.getFirst());
        assertEquals(1, choices.stream().filter(c -> c.id().equals(retained.id())).count());
        assertDoesNotThrow(() -> new PolicyFrame(UUID.randomUUID(), 10, new double[PolicyFrame.STATE_SIZE],
                choices.stream().map(PolicyCandidates.Candidate::encoded).toList(), false));
    }

    @Test void largeInventoriesCannotHideAllCraftingChoicesAtTheCandidateLimit() {
        var all = new java.util.ArrayList<PolicyCandidates.Candidate>();
        for (int i = 0; i < 150; i++) all.add(new PolicyCandidates.Candidate("move:" + i,
                new me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.MoveTo(new me.herry.minecraftAI.ai.util.BlockPoint(i, 64, 0), 1),
                null, null, new PolicyFrame.Option("move:" + i, new double[PolicyFrame.ACTION_SIZE])));
        var recipe = new me.herry.minecraftAI.ai.crafting.RecipeOption("recipe", "STICK", 4, false, false,
                List.of(new me.herry.minecraftAI.ai.crafting.RecipeOption.Ingredient(2, List.of("OAK_PLANKS"), false)));
        all.add(new PolicyCandidates.Candidate("craft:recipe", null, recipe, null,
                new PolicyFrame.Option("craft:recipe", new double[PolicyFrame.ACTION_SIZE])));
        var selected = PolicyCandidates.selectFair(all, "move:149");
        assertEquals(PolicyFrame.MAX_OPTIONS, selected.size());
        assertEquals("move:149", selected.getFirst().id());
        assertTrue(selected.stream().anyMatch(c -> c.id().equals("craft:recipe")));
    }
}
