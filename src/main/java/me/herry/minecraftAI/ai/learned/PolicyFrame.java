package me.herry.minecraftAI.ai.learned;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Value-only inputs captured before selection. No Teacher label, score, future result or live world reference. */
public record PolicyFrame(UUID world, long serverTick, double[] state, List<Option> options, boolean complete, Discovery discovery,
                          PolicyTaskMemory.View taskMemory) {
    public PolicyFrame(UUID world, long serverTick, double[] state, List<Option> options, boolean complete, Discovery discovery) {
        this(world, serverTick, state, options, complete, discovery, null);
    }
    public static final String CONTRACT = "minecraft-primitive-candidates/v6";
    public static final int STATE_SIZE = 400;
    public static final int ACTION_SIZE = 256;
    public static final int INPUT_SIZE = STATE_SIZE + ACTION_SIZE;
    public static final int MAX_OPTIONS = 128;

    public PolicyFrame(UUID world, long serverTick, double[] state, List<Option> options, boolean complete) {
        this(world, serverTick, state, options, complete, null);
    }

    /** Optional evidence only; not concatenated into the actor or critic input. Negative counts mean not visited. */
    public record Discovery(String schema, List<String> visitedSources, Map<String, Integer> generatedBySource,
                            boolean budgetExhausted, int recipeOptions, int recipesChecked, boolean recipeScanComplete,
                            ConsumptionScan consumption) {
        public Discovery(String schema, List<String> visitedSources, Map<String, Integer> generatedBySource,
                         boolean budgetExhausted, int recipeOptions, int recipesChecked, boolean recipeScanComplete) {
            this(schema, visitedSources, generatedBySource, budgetExhausted, recipeOptions, recipesChecked, recipeScanComplete, null);
        }
        public Discovery {
            if (!"candidate-discovery/v1".equals(schema) || recipeOptions < -1 || recipesChecked < -1)
                throw new IllegalArgumentException("invalid discovery evidence");
            visitedSources = List.copyOf(visitedSources);
            generatedBySource = Map.copyOf(generatedBySource);
        }
    }

    /** Counts only inspected storage slots. Missing evidence means the source was not visited. */
    public record ConsumptionScan(int snapshotSlots, int inspectedSlots, int emptySlots, int nonConsumableSlots,
                                  int hungerBlockedSlots, int otherBlockedSlots, int unknownSlots,
                                  int unsupportedSlots, int availableSlots) {
        public ConsumptionScan {
            int total = 0;
            for (int count : new int[]{emptySlots, nonConsumableSlots, hungerBlockedSlots, otherBlockedSlots,
                    unknownSlots, unsupportedSlots, availableSlots}) {
                if (count < 0 || count > 36) throw new IllegalArgumentException("invalid consumption count");
                total += count;
            }
            if (snapshotSlots < 0 || snapshotSlots > 36 || inspectedSlots < 0 || inspectedSlots > snapshotSlots || total != inspectedSlots)
                throw new IllegalArgumentException("invalid consumption coverage");
        }
        public boolean complete() { return snapshotSlots == 36 && inspectedSlots == 36; }
    }

    public PolicyFrame {
        Objects.requireNonNull(world);
        if (serverTick < 0 || serverTick > 0xffff_ffffL) throw new IllegalArgumentException("invalid server tick");
        state = vector(state, STATE_SIZE);
        options = List.copyOf(options);
        if (options.isEmpty() || options.size() > MAX_OPTIONS || options.stream().map(Option::id).distinct().count() != options.size())
            throw new IllegalArgumentException("invalid candidate set");
    }
    @Override public double[] state() { return state.clone(); }

    /** Exact capture evidence, never a policy score or a claim about later world state. */
    public record BlockEvidence(String material, UUID world, int x, int y, int z, long observedTick) {
        public BlockEvidence {
            Objects.requireNonNull(world);
            if (material == null || !material.matches("[A-Z][A-Z0-9_]{0,127}")
                    || observedTick < 0 || observedTick > 0xffff_ffffL)
                throw new IllegalArgumentException("invalid observed block evidence");
        }
    }

    public record Option(String id, double[] features, BlockEvidence blockEvidence) {
        public Option(String id, double[] features) { this(id, features, null); }
        public Option {
            Objects.requireNonNull(id);
            if (id.isBlank() || id.length() > 256) throw new IllegalArgumentException("invalid candidate identity");
            features = vector(features, ACTION_SIZE);
        }
        @Override public double[] features() { return features.clone(); }
    }

    public double[] input(int option) {
        double[] input = new double[INPUT_SIZE];
        System.arraycopy(state, 0, input, 0, STATE_SIZE);
        System.arraycopy(options.get(option).features, 0, input, STATE_SIZE, ACTION_SIZE);
        return input;
    }

    private static double[] vector(double[] values, int size) {
        Objects.requireNonNull(values);
        if (values.length != size) throw new IllegalArgumentException("feature shape mismatch");
        double[] result = values.clone();
        for (double value : result) if (!Double.isFinite(value) || Math.abs(value) > 16)
            throw new IllegalArgumentException("invalid feature value");
        return result;
    }
}
