package me.herry.minecraftAI.ai.learned;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Value-only inputs captured before selection. No Teacher label, score, future result or live world reference. */
public record PolicyFrame(UUID world, long serverTick, double[] state, List<Option> options, boolean complete) {
    public static final String CONTRACT = "minecraft-primitive-candidates/v1";
    public static final int STATE_SIZE = 64;
    public static final int ACTION_SIZE = 256;
    public static final int INPUT_SIZE = STATE_SIZE + ACTION_SIZE;
    public static final int MAX_OPTIONS = 128;

    public PolicyFrame {
        Objects.requireNonNull(world);
        if (serverTick < 0 || serverTick > 0xffff_ffffL) throw new IllegalArgumentException("invalid server tick");
        state = vector(state, STATE_SIZE);
        options = List.copyOf(options);
        if (options.isEmpty() || options.size() > MAX_OPTIONS || options.stream().map(Option::id).distinct().count() != options.size())
            throw new IllegalArgumentException("invalid candidate set");
    }
    @Override public double[] state() { return state.clone(); }

    public record Option(String id, double[] features) {
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
