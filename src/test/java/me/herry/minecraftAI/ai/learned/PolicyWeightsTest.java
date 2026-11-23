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
    @Test
    void offAxisSightUsesTheSameBoundedWindowAndKeepsLocalCoverage() {
        var feet = new me.herry.minecraftAI.ai.util.BlockPoint(0, 64, 0);
        var crosshair = feet.offset(0, 1, 4);
        var offAxis = feet.offset(2, 1, 3);
        var local = PolicyCandidates.nearbyTargets(feet).getFirst();
        var targets = PolicyCandidates.miningObservationTargets(feet, crosshair, 0,
                List.of(offAxis, local, offAxis, crosshair));
        assertEquals(24, targets.size());
        assertEquals(List.of(crosshair, local, offAxis), targets.subList(0, 3));
        assertEquals(1, PolicyCandidates.nextMiningObservationCursor(feet, 0, targets.subList(0, 3)));
    }

    @Test
    void partialMiningInspectionResumesWithoutSkippingUnprocessedCells() {
        var feet = new me.herry.minecraftAI.ai.util.BlockPoint(0, 64, 0);
        int total = PolicyCandidates.nearbyTargets(feet).size();
        assertEquals(PolicyCandidates.NEARBY_TARGET_COUNT, total);
        assertTrue(PolicyCandidates.nearbyTargets(feet).contains(feet.offset(1, -1, 0)));
        int cursor = 0;
        var covered = new java.util.HashSet<me.herry.minecraftAI.ai.util.BlockPoint>();
        for (int visit = 0; visit < total; visit++) {
            var targets = PolicyCandidates.miningObservationTargets(feet, null, cursor);
            var processed = targets.subList(0, 1);
            covered.addAll(processed);
            cursor = PolicyCandidates.nextMiningObservationCursor(feet, cursor, processed);
        }
        assertEquals(total, covered.size());
        assertEquals(0, cursor);
        assertEquals(17, PolicyCandidates.nextMiningObservationCursor(feet, 17, java.util.List.of()));
        var crosshair = feet.offset(0, 1, 4);
        assertEquals(17, PolicyCandidates.nextMiningObservationCursor(feet, 17, java.util.List.of(crosshair)));
    }

    @Test
    void inVolumeSightIsInspectedEvenWhenTheRotatingPrefixMissesIt() {
        var feet = new me.herry.minecraftAI.ai.util.BlockPoint(-948, 63, -143);
        var viewed = feet.offset(-1, 1, 0);
        int cursor = 43;
        assertTrue(PolicyCandidates.nearbyTargets(feet).contains(viewed));
        var rotating = PolicyCandidates.miningObservationTargets(feet, null, cursor);
        assertFalse(rotating.contains(viewed));
        var targets = PolicyCandidates.miningObservationTargets(feet, null, cursor, List.of(viewed, viewed));
        assertEquals(24, targets.size());
        assertEquals(List.of(rotating.getFirst(), viewed), targets.subList(0, 2));
        assertEquals(cursor + 1, PolicyCandidates.nextMiningObservationCursor(feet, cursor, targets.subList(0, 2)));
    }

    @Test
    void stationaryMiningObservationCoversTheLocalVolumeWithoutMaterialAdvice() {
        var feet = new me.herry.minecraftAI.ai.util.BlockPoint(0, 64, 0);
        var distant = feet.offset(1, 1, 2);
        assertFalse(PolicyCandidates.nearbyTargets(feet).subList(0, 24).contains(distant));
        var covered = new java.util.HashSet<me.herry.minecraftAI.ai.util.BlockPoint>();
        for (int cursor = 0; cursor < 120; cursor += 24) {
            var targets = PolicyCandidates.miningObservationTargets(feet, null, cursor);
            assertEquals(24, targets.size());
            covered.addAll(targets);
        }
        assertEquals(new java.util.HashSet<>(PolicyCandidates.nearbyTargets(feet)), covered);
        var crosshair = feet.offset(0, 1, 4);
        covered.clear();
        for (int cursor = 0; cursor < 115; cursor += 23) {
            var targets = PolicyCandidates.miningObservationTargets(feet, crosshair, cursor);
            assertEquals(crosshair, targets.getFirst());
            assertEquals(24, targets.size());
            covered.addAll(targets);
        }
        assertTrue(covered.containsAll(PolicyCandidates.nearbyTargets(feet)));
    }

    @Test
    void placementOffsetsKeepCardinalFeetCellsAndCoverVerticalBridgingAndPortalHeights() {
        var window0 = PolicyCandidates.placementOffsets(0, 8);
        assertEquals(8, window0.size());
        assertEquals(new me.herry.minecraftAI.ai.util.BlockPoint(1, 0, 0), window0.getFirst());
        var covered = new java.util.HashSet<me.herry.minecraftAI.ai.util.BlockPoint>();
        for (int c = 0; c < 140; c++)
            covered.addAll(PolicyCandidates.placementOffsets(c, 8));
        assertTrue(covered.contains(new me.herry.minecraftAI.ai.util.BlockPoint(-1, 0, 0)));
        assertTrue(covered.contains(new me.herry.minecraftAI.ai.util.BlockPoint(1, -1, 0)));
        assertTrue(covered.contains(new me.herry.minecraftAI.ai.util.BlockPoint(1, 2, 0)));
        assertTrue(covered.contains(new me.herry.minecraftAI.ai.util.BlockPoint(1, 3, 0)));
    }

    @Test
    void goalActionResidualUsesLearnedCrossTermsAndRejectsOldSchema() throws Exception {
        double[][] cross = new double[24][PolicyFrame.ACTION_SIZE];
        cross[8][0] = 2;
        var artifact = new PolicyWeights.Artifact("minecraft-rl-actor/v3", PolicyFrame.CONTRACT, "ppo",
                "minecraft-paper", 1, "numerical-fixture", "0".repeat(64), new double[1][PolicyFrame.INPUT_SIZE],
                new double[] { 0 }, new double[] { 0 }, 0, null, null, null, null, null, "goal-action-bilinear/v1",
                cross);
        Path file = directory.resolve("goal-cross.json");
        Files.writeString(file, new Gson().toJson(artifact));
        double[] state = new double[PolicyFrame.STATE_SIZE];
        state[216] = 1;
        double[] left = new double[PolicyFrame.ACTION_SIZE], right = new double[PolicyFrame.ACTION_SIZE];
        left[0] = -.5;
        right[0] = .5;
        var observed = new PolicyFrame(UUID.randomUUID(), 5, state,
                java.util.List.of(new PolicyFrame.Option("left", left), new PolicyFrame.Option("right", right)), false);
        var prediction = PolicyWeights.load(file).predict(observed);
        assertEquals(1, prediction.index());
        assertEquals(-Math.log1p(Math.exp(-2)), prediction.logProbability(), 1e-12);
        String oldSchema = new Gson().toJson(artifact).replace("minecraft-rl-actor/v3", "minecraft-rl-actor/v1");
        Files.writeString(file, oldSchema);
        assertThrows(java.io.IOException.class, () -> PolicyWeights.load(file));
    }

    @Test
    void activeMiningCanChangeReconsiderationWithoutInventingFreshEvidence() {
        var point = new me.herry.minecraftAI.ai.util.BlockPoint(1, 64, 2);
        var tool = new me.herry.minecraftAI.ai.primitive.control.ToolChoice(0, "AIR", java.util.Map.of());
        var command = new me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.BreakBlock(point, tool);
        var evidence = new PolicyFrame.BlockEvidence("OAK_LOG", UUID.randomUUID(), 1, 64, 2, 100);
        double[] features = new double[PolicyFrame.ACTION_SIZE];
        features[55] = 1;
        var current = new PolicyCandidates.Candidate("mine:1,64,2:0", command, null, null,
                new PolicyFrame.Option("mine:1,64,2:0", features, evidence)).committed(5);
        var variants = PolicyCandidates.compatibleReconsiderations(current);
        assertEquals(List.of(20, 80), variants.stream().map(PolicyCandidates.Candidate::reconsiderAfterTicks).toList());
        for (var variant : variants) {
            assertEquals(current.inputId(), variant.inputId());
            assertNotEquals(current.id(), variant.id());
            assertEquals(0, variant.encoded().features()[55]);
            assertSame(evidence, variant.encoded().blockEvidence());
            assertEquals(variant.commitmentTicks(), variant.reconsiderAfterTicks());
        }
    }

    @Test
    void compatibleExecutionVariantsShareTheContinueGroup() throws Exception {
        var artifact = new PolicyWeights.Artifact("minecraft-rl-actor/v2", PolicyFrame.CONTRACT, "ppo",
                "minecraft-paper", 1, "numerical-fixture", "0".repeat(64), new double[1][PolicyFrame.INPUT_SIZE],
                new double[] { 0 }, new double[] { 0 }, 0, null, null, null, null, "continue-switch-mean/v2");
        Path file = directory.resolve("temporal-group.json");
        Files.writeString(file, new Gson().toJson(artifact));
        double[] state = new double[PolicyFrame.STATE_SIZE];
        state[400] = state[401] = 1;
        var options = new java.util.ArrayList<PolicyFrame.Option>();
        for (int i = 0; i < 6; i++) {
            double[] f = new double[PolicyFrame.ACTION_SIZE];
            f[55] = i == 0 ? 1 : 0;
            options.add(new PolicyFrame.Option("choice" + i, f, null, i < 2 ? "same-mining-input" : "different" + i));
        }
        var observed = new PolicyFrame(UUID.randomUUID(), 5, state, options, false);
        var predicted = PolicyWeights.load(file).sample(observed, new java.util.Random() {
            @Override
            public double nextDouble() {
                return 0.3;
            }
        });
        assertEquals(1, predicted.index());
        assertEquals(Math.log(0.25), predicted.logProbability(), 1e-12);
    }

    @Test
    void temporalActorUsesSameTwoStageProbabilitiesAndAllowsSwitching() throws Exception {
        var artifact = new PolicyWeights.Artifact("minecraft-rl-actor/v2", PolicyFrame.CONTRACT, "ppo",
                "minecraft-paper", 1, "numerical-fixture", "0".repeat(64), new double[1][PolicyFrame.INPUT_SIZE],
                new double[] { 0 }, new double[] { 0 }, 0, null, null, null, null, "continue-switch-mean/v1");
        Path file = directory.resolve("temporal.json");
        Files.writeString(file, new Gson().toJson(artifact));
        double[] state = new double[PolicyFrame.STATE_SIZE];
        state[400] = state[401] = 1;
        var options = new java.util.ArrayList<PolicyFrame.Option>();
        for (int i = 0; i < 4; i++) {
            double[] features = new double[PolicyFrame.ACTION_SIZE];
            features[55] = i == 0 ? 1 : 0;
            options.add(new PolicyFrame.Option("choice" + i, features));
        }
        var observed = new PolicyFrame(UUID.randomUUID(), 5, state, options, false);
        var weights = PolicyWeights.load(file);
        var continued = weights.sample(observed, new java.util.Random() {
            @Override
            public double nextDouble() {
                return 0.1;
            }
        });
        assertEquals(0, continued.index());
        assertEquals(Math.log(0.5), continued.logProbability(), 1e-12);
        var switched = weights.sample(observed, new java.util.Random() {
            @Override
            public double nextDouble() {
                return 0.9;
            }
        });
        assertNotEquals(0, switched.index());
        assertEquals(Math.log(1. / 6), switched.logProbability(), 1e-12);
    }

    @TempDir
    Path directory;

    /**
     * Synthetic numerical fixture only; never a model trained or deployed in
     * Minecraft.
     */
    private Path fixture(long steps, String environment, double weight) throws Exception {
        double[][] hidden = new double[1][PolicyFrame.INPUT_SIZE];
        hidden[0][PolicyFrame.STATE_SIZE] = weight;
        var artifact = new PolicyWeights.Artifact(PolicyWeights.SCHEMA, PolicyFrame.CONTRACT, "ppo", environment,
                steps, "numerical-unit-fixture-not-training", "0".repeat(64), hidden, new double[] { 0 },
                new double[] { 1 }, 0);
        Path file = directory.resolve("actor-" + steps + "-" + weight + "-" + environment + ".json");
        Files.writeString(file, new Gson().toJson(artifact));
        return file;
    }

    private PolicyFrame frame() {
        double[] left = new double[PolicyFrame.ACTION_SIZE];
        left[0] = -1;
        double[] right = new double[PolicyFrame.ACTION_SIZE];
        right[0] = 1;
        return new PolicyFrame(UUID.randomUUID(), 5, new double[PolicyFrame.STATE_SIZE],
                List.of(new PolicyFrame.Option("left", left), new PolicyFrame.Option("right", right)), false);
    }

    @Test
    void swappingTheLearnedParameterChangesTheSelectedActionWithoutAnyGoalOrCostRule() throws Exception {
        var positive = PolicyWeights.load(fixture(1, "minecraft-paper", 2)).predict(frame());
        var negative = PolicyWeights.load(fixture(1, "minecraft-paper", -2)).predict(frame());
        assertEquals("right", positive.candidate());
        assertEquals("left", negative.candidate());
        assertTrue(Double.isFinite(positive.logProbability()));
        assertTrue(positive.logProbability() <= 0);
        assertNotEquals(positive.modelSha256(), negative.modelSha256());
    }

    @Test
    void initializationOrAnotherEnvironmentCannotMasqueradeAsATrainedPaperPolicy() throws Exception {
        Path untrained = fixture(0, "minecraft-paper", 1);
        Path synthetic = fixture(10, "toy-simulator", 1);
        assertThrows(java.io.IOException.class, () -> PolicyWeights.load(untrained));
        assertThrows(java.io.IOException.class, () -> PolicyWeights.load(synthetic));
        assertThrows(java.io.IOException.class, () -> PolicyWeights.load(directory.resolve("missing.json")));
        Path malformed = directory.resolve("malformed.json");
        Files.writeString(malformed, "{}");
        assertThrows(java.io.IOException.class, () -> PolicyWeights.load(malformed));
    }

    @Test
    void missingLegacyCountersAreReportedAsUnknownRatherThanInventedTrainingProgress() throws Exception {
        var policy = PolicyWeights.load(fixture(1, "minecraft-paper", 1));
        assertTrue(policy.details().contains("정책 버전: 미기록"));
        assertTrue(policy.details().contains("가중치 갱신: 미기록"));
        assertTrue(policy.details().contains("가중치 학습에 사용한 전이: 1"));
    }

    @Test
    void categoricalExecutionUsesTheLearnedProbabilitiesAndReportsTheChosenLogProbability() throws Exception {
        var policy = PolicyWeights.load(fixture(1, "minecraft-paper", 2));
        var observed = frame();
        var random = new java.util.Random(741);
        int right = 0;
        double margin = 2 * Math.tanh(2);
        double expectedRight = 1 / (1 + Math.exp(-margin));
        for (int i = 0; i < 2000; i++) {
            var selected = policy.sample(observed, random);
            boolean isRight = selected.candidate().equals("right");
            if (isRight)
                right++;
            assertEquals(Math.log(isRight ? expectedRight : 1 - expectedRight), selected.logProbability(), 1e-12);
        }
        assertEquals(expectedRight, right / 2000.0, 0.04);
        assertTrue(right < 2000, "categorical execution must not silently become greedy argmax");
    }

    @Test
    void observationsAndCandidateFeaturesCannotBeChangedAfterSelectionInputWasCaptured() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        double[] action = new double[PolicyFrame.ACTION_SIZE];
        action[0] = 1;
        var option = new PolicyFrame.Option("action", action);
        var frame = new PolicyFrame(UUID.randomUUID(), 1, state, List.of(option), true);
        state[0] = 9;
        action[0] = 9;
        frame.state()[0] = 9;
        option.features()[0] = 9;
        assertEquals(0, frame.input(0)[0]);
        assertEquals(1, frame.input(0)[PolicyFrame.STATE_SIZE]);
        assertThrows(IllegalArgumentException.class, () -> new PolicyFrame(UUID.randomUUID(), 1,
                new double[1], List.of(option), true));
    }

    @Test
    void retainingAnInputAlreadyEnumeratedDoesNotDuplicateTheCandidateOrInvalidateTheFrame() {
        var raw = new PolicyCandidates.Candidate("wait:5",
                new me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.Wait(5), null, null,
                new PolicyFrame.Option("wait:5", new double[PolicyFrame.ACTION_SIZE]));
        var generated = raw.committed(20);
        double[] retainedFeatures = generated.encoded().features();
        retainedFeatures[55] = 1;
        var retained = new PolicyCandidates.Candidate(generated.id(), generated.command(), null, null,
                new PolicyFrame.Option(generated.id(), retainedFeatures), 20, generated.inputId());
        var choices = PolicyCandidates.selectFair(List.of(retained, raw.committed(5), generated, raw.committed(80)),
                retained.id());
        assertEquals(3, choices.size());
        assertSame(retained, choices.getFirst());
        assertEquals(1, choices.stream().filter(c -> c.id().equals(retained.id())).count());
        assertDoesNotThrow(() -> new PolicyFrame(UUID.randomUUID(), 10, new double[PolicyFrame.STATE_SIZE],
                choices.stream().map(PolicyCandidates.Candidate::encoded).toList(), false));
    }

    @Test
    void emptyHandsCannotCrowdOutOccupiedSlotsAndNearbyInspectionCoversAllDirections() {
        var slots = new java.util.ArrayList<me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot>();
        for (int i = 0; i < 34; i++)
            slots.add(new me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot(
                    i, "AIR", 0, 0, 0, java.util.Map.of()));
        for (int i : new int[] { 34, 35 })
            slots.add(new me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot(
                    i, "IRON_PICKAXE", 1, 0, 250, java.util.Map.of()));
        var tools = PolicyCandidates.miningSlots(slots);
        assertEquals(List.of(0, 34, 35), tools.stream().map(slot -> slot.slot()).toList());
        var feet = new me.herry.minecraftAI.ai.util.BlockPoint(10, 70, -15);
        var local = PolicyCandidates.nearbyTargets(feet).stream().limit(23).toList();
        assertTrue(local.stream().anyMatch(p -> p.x() < feet.x()));
        assertTrue(local.stream().anyMatch(p -> p.x() > feet.x()));
        assertTrue(local.stream().anyMatch(p -> p.z() < feet.z()));
        assertTrue(local.stream().anyMatch(p -> p.z() > feet.z()));
    }

    @Test
    void largeInventoriesCannotHideAllCraftingChoicesAtTheCandidateLimit() {
        var all = new java.util.ArrayList<PolicyCandidates.Candidate>();
        for (int i = 0; i < 150; i++)
            all.add(new PolicyCandidates.Candidate("move:" + i,
                    new me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.MoveTo(
                            new me.herry.minecraftAI.ai.util.BlockPoint(i, 64, 0), 1),
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

    @Test
    void equipmentSourceSlotsDeduplicatesEmptyHotbarAndStorageWhileKeepingOccupiedSlots() {
        int[] sparse = new int[41];
        sparse[0] = 8; // held OAK_PLANKS
        sparse[1] = 4; // STICK
        List<Integer> selected = PolicyCandidates.equipmentSourceSlots(0, 0, sparse, 8);
        // Occupied (0, 1) + at most 1 empty hotbar representative + at most 1 empty storage representative
        assertEquals(4, selected.size());
        assertTrue(selected.contains(0));
        assertTrue(selected.contains(1));
        assertEquals(1, selected.stream().filter(s -> s < 9 && sparse[s] == 0).count());
        assertEquals(1, selected.stream().filter(s -> s >= 9 && s < 36 && sparse[s] == 0).count());

        // When held slot is empty and only armor/offhand is empty, still bounded without held duplication
        sparse[0] = 0;
        List<Integer> emptyHeld = PolicyCandidates.equipmentSourceSlots(0, 0, sparse, 8);
        assertFalse(emptyHeld.contains(0));
        assertEquals(1, emptyHeld.stream().filter(s -> s < 9 && sparse[s] == 0).count());

        // Rotating cursor covers all occupied slots when more than limit=8 slots are occupied
        int[] dense = new int[41];
        for (int s = 0; s < 12; s++)
            dense[s] = 1;
        var covered = new java.util.HashSet<Integer>();
        for (int cursor = 0; cursor < 41; cursor++)
            covered.addAll(PolicyCandidates.equipmentSourceSlots(cursor, 0, dense, 8));
        for (int s = 0; s < 12; s++)
            assertTrue(covered.contains(s), "slot " + s + " must be covered across rotation");
    }

    @Test
    void equipmentCandidateCapRotatesAcrossAllSlotTargetPairs() {
        assertEquals(4, PolicyCandidates.MAX_EQUIPMENT_CANDIDATES);
        for (int pairs : new int[] { 1, 2, 6, 8, 12, 24, 30 }) {
            var visited = new java.util.HashSet<Integer>();
            int cursor = 0;
            for (int tick = 0; tick < pairs; tick++) {
                int offer = Math.min(pairs, PolicyCandidates.MAX_EQUIPMENT_CANDIDATES);
                for (int step = 0; step < offer; step++)
                    visited.add(PolicyCandidates.equipmentPairIndex(cursor, step, pairs));
                cursor = PolicyCandidates.nextEquipmentCursor(cursor, offer, pairs);
            }
            for (int p = 0; p < pairs; p++)
                assertTrue(visited.contains(p), "pair " + p + " of " + pairs + " must be visited");
        }
    }

    @Test
    void miningPairBijectionCoversAllBlockToolPairsAndSpreadsBlocksFirst() {
        assertEquals(12, PolicyCandidates.MAX_ABOVE_FLOOR_MINING_CANDIDATES);
        assertEquals(6, PolicyCandidates.MAX_BELOW_FLOOR_MINING_CANDIDATES);
        for (int[] shape : new int[][] { { 1, 1 }, { 4, 6 }, { 6, 12 }, { 8, 8 }, { 10, 15 } }) {
            int blocks = shape[0], tools = shape[1], pairs = blocks * tools;
            var allPairs = new java.util.HashSet<String>();
            for (int p = 0; p < pairs; p++) {
                int b = PolicyCandidates.miningBlockIndex(p, blocks);
                int t = PolicyCandidates.miningToolIndex(p, blocks, tools);
                assertTrue(allPairs.add(b + ":" + t), "duplicate pair for shape " + blocks + "x" + tools);
            }
            assertEquals(pairs, allPairs.size());

            int window = Math.min(blocks, 6);
            for (int start = 0; start < pairs; start++) {
                var distinctBlocks = new java.util.HashSet<Integer>();
                for (int w = 0; w < window; w++)
                    distinctBlocks.add(PolicyCandidates.miningBlockIndex((start + w) % pairs, blocks));
                assertEquals(window, distinctBlocks.size());
            }

            var visited = new java.util.HashSet<Integer>();
            int cursor = 0, step = Math.min(pairs, 6);
            for (int tick = 0; tick < pairs; tick++) {
                for (int s = 0; s < step; s++)
                    visited.add(Math.floorMod(cursor + s, pairs));
                cursor = PolicyCandidates.nextCoprimeCursor(cursor, step, pairs);
            }
            assertEquals(pairs, visited.size());
        }
    }

    @Test
    void nearbyTargetsIncludeReachableDeepFloorAndLocalMoveCapRotatesAllOffsets() {
        assertEquals(114, PolicyCandidates.NEARBY_TARGET_COUNT);
        assertEquals(3, PolicyCandidates.MAX_LOCAL_MOVE_CANDIDATES);
        var feet = new me.herry.minecraftAI.ai.util.BlockPoint(10, 70, -15);
        var targets = PolicyCandidates.nearbyTargets(feet);
        assertEquals(114, targets.size());
        assertTrue(targets.contains(feet.offset(0, -2, 0)));
        assertTrue(targets.contains(feet.offset(1, -2, -1)));
        assertTrue(targets.contains(feet.offset(0, -3, 0)));
        assertTrue(targets.contains(feet.offset(1, -3, 0)));
        assertTrue(targets.contains(feet.offset(0, -3, -1)));

        var visited = new java.util.HashSet<Integer>();
        int cursor = 0;
        for (int tick = 0; tick < 48; tick++) {
            for (int s = 0; s < PolicyCandidates.MAX_LOCAL_MOVE_CANDIDATES; s++)
                visited.add(Math.floorMod(cursor + s, 48));
            cursor = PolicyCandidates.nextCoprimeCursor(cursor, PolicyCandidates.MAX_LOCAL_MOVE_CANDIDATES, 48);
        }
        assertEquals(48, visited.size());
    }
}
