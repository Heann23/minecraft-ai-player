package me.herry.minecraftAI.ai.learned;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared candidate actor: scores come exclusively from trained neural weights, never hand-written utility. */
public final class PolicyWeights {
    public static final String SCHEMA = "minecraft-rl-actor/v1";
    public record Artifact(String schema, String contract, String algorithm, String environment,
                           long environmentSteps, String trainingRun, String rolloutSha256,
                           double[][] hiddenWeights, double[] hiddenBias, double[] outputWeights, double outputBias) {}
    public record Prediction(int index, String candidate, double logProbability, String modelSha256) {}
    private final Artifact artifact;
    private final String sha256;

    private PolicyWeights(Artifact artifact, String sha256) {
        if (artifact == null || !SCHEMA.equals(artifact.schema()) || !PolicyFrame.CONTRACT.equals(artifact.contract())
                || !"ppo".equals(artifact.algorithm()) || !"minecraft-paper".equals(artifact.environment())
                || artifact.environmentSteps() <= 0 || artifact.trainingRun() == null || artifact.trainingRun().isBlank()
                || artifact.rolloutSha256() == null || !artifact.rolloutSha256().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("missing/incompatible trained Paper RL provenance");
        int hidden = artifact.hiddenBias() == null ? 0 : artifact.hiddenBias().length;
        if (hidden < 1 || hidden > 256 || artifact.hiddenWeights() == null || artifact.hiddenWeights().length != hidden
                || artifact.outputWeights() == null || artifact.outputWeights().length != hidden)
            throw new IllegalArgumentException("invalid actor shape");
        for (int row = 0; row < hidden; row++) {
            finite(artifact.hiddenBias()[row]); finite(artifact.outputWeights()[row]);
            if (artifact.hiddenWeights()[row] == null || artifact.hiddenWeights()[row].length != PolicyFrame.INPUT_SIZE)
                throw new IllegalArgumentException("actor input contract mismatch");
            for (double weight : artifact.hiddenWeights()[row]) finite(weight);
        }
        finite(artifact.outputBias());
        this.artifact = artifact; this.sha256 = sha256;
    }

    /** Read once when the controller starts; no model I/O or deserialization in a world tick. */
    public static PolicyWeights load(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 8 * 1024 * 1024) throw new IOException("RL model missing or too large");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("RL model grew beyond size limit");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new PolicyWeights(new Gson().fromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Artifact.class), hash);
        } catch (IllegalArgumentException | com.google.gson.JsonParseException e) {
            throw new IOException("incompatible RL model: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public Prediction predict(PolicyFrame frame) {
        double[] scores = new double[frame.options().size()];
        int best = 0;
        for (int option = 0; option < scores.length; option++) {
            double[] input = frame.input(option);
            double score = artifact.outputBias();
            for (int neuron = 0; neuron < artifact.hiddenBias().length; neuron++) {
                double value = artifact.hiddenBias()[neuron];
                for (int feature = 0; feature < input.length; feature++) value += artifact.hiddenWeights()[neuron][feature] * input[feature];
                score += Math.tanh(value) * artifact.outputWeights()[neuron];
            }
            if (!Double.isFinite(score)) throw new IllegalStateException("non-finite actor output");
            scores[option] = score;
            if (scores[option] > scores[best]) best = option;
        }
        double total = 0;
        for (double score : scores) total += Math.exp(score - scores[best]);
        return new Prediction(best, frame.options().get(best).id(), -Math.log(total), sha256);
    }

    public String sha256() { return sha256; }
    public long environmentSteps() { return artifact.environmentSteps(); }
    public String trainingRun() { return artifact.trainingRun(); }

    private static void finite(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 1e6) throw new IllegalArgumentException("invalid actor parameter");
    }
}
