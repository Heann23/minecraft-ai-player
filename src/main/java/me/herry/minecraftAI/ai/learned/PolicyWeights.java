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
                           double[][] hiddenWeights, double[] hiddenBias, double[] outputWeights, double outputBias,
                           String brainId, String trainingStartState, Long modelVersion, Long gradientUpdates) {
        public Artifact(String schema, String contract, String algorithm, String environment,
                        long environmentSteps, String trainingRun, String rolloutSha256,
                        double[][] hiddenWeights, double[] hiddenBias, double[] outputWeights, double outputBias) {
            this(schema, contract, algorithm, environment, environmentSteps, trainingRun, rolloutSha256,
                    hiddenWeights, hiddenBias, outputWeights, outputBias, null, null, null, null);
        }
    }
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
        if (artifact.brainId() != null) java.util.UUID.fromString(artifact.brainId());
        if (artifact.trainingStartState() != null && !java.util.Set.of("fresh", "pretrained", "resume").contains(artifact.trainingStartState()))
            throw new IllegalArgumentException("invalid training start state");
        if (artifact.modelVersion() != null && artifact.modelVersion() < 0 || artifact.gradientUpdates() != null && artifact.gradientUpdates() <= 0)
            throw new IllegalArgumentException("invalid policy counters");
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
        return select(frame, null);
    }

    /** Match PPO's categorical behavior policy. Greedy predict remains available for diagnostics. */
    public Prediction sample(PolicyFrame frame, java.util.random.RandomGenerator random) {
        return select(frame, java.util.Objects.requireNonNull(random));
    }

    private Prediction select(PolicyFrame frame, java.util.random.RandomGenerator random) {
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
        int chosen = best;
        if (random != null) {
            double draw = random.nextDouble() * total;
            chosen = scores.length - 1;
            for (int option = 0; option < scores.length; option++) {
                draw -= Math.exp(scores[option] - scores[best]);
                if (draw < 0) { chosen = option; break; }
            }
        }
        return new Prediction(chosen, frame.options().get(chosen).id(), scores[chosen] - scores[best] - Math.log(total), sha256);
    }

    public String sha256() { return sha256; }
    public long environmentSteps() { return artifact.environmentSteps(); }
    public String trainingRun() { return artifact.trainingRun(); }
    public java.util.List<String> details() {
        return java.util.List.of("두뇌 ID: " + (artifact.brainId() == null ? "이전 모델에 미기록" : artifact.brainId()),
                "실행 상태: 고정 가중치 추론 / 학습 비활성",
                "정책 버전: " + (artifact.modelVersion() == null ? "미기록" : artifact.modelVersion()),
                "누적 전이: " + artifact.environmentSteps(),
                "가중치 갱신: " + (artifact.gradientUpdates() == null ? "미기록" : artifact.gradientUpdates()),
                "모델 해시: " + sha256, "학습 run: " + artifact.trainingRun(),
                "학습 당시 시작 상태: " + (artifact.trainingStartState() == null ? "미기록" : artifact.trainingStartState()),
                "게임 성능: 별도 실제 평가 필요");
    }

    private static void finite(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 1e6) throw new IllegalArgumentException("invalid actor parameter");
    }
}
