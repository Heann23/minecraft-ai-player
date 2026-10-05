package me.herry.minecraftAI.ai.policy;

import me.herry.minecraftAI.ai.experience.Experience;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.observation.Observation;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 후보 정책을 기존 규칙(Teacher)과 같은 관측으로 같이 돌려서 무엇을 골랐을지 받아 둔다. 실행은 하지 않는다.
 *
 * 후보가 예외를 내거나 너무 오래 걸려도 AI 는 Teacher 의 판단대로 계속 움직인다.
 * 그런 일이 거듭되면 후보를 꺼 버리고 한 번 알린다 (서버 틱을 계속 잡아먹게 두지 않는다).
 */
public final class ShadowRunner {
    // 판단 한 번에 이보다 오래 걸리면 느린 것으로 센다.
    static final long SLOW_NANOS = 2_000_000L;
    // 오류나 느린 판단이 이만큼 이어지면 끈다.
    static final int MAX_STRIKES = 20;
    private static final int ERROR_LENGTH = 200;

    /**
     * @param compared Teacher 가 스스로 고른 판단 중 후보도 답을 낸 것의 수
     * @param agreed   그 가운데 같은 목표를 고른 수
     * @param failed   후보가 오류를 낸 수
     */
    public record Stats(String policy, String version, boolean enabled, long compared, long agreed, long failed) {
        public double agreement() {
            return compared == 0 ? 0.0 : (double) agreed / compared;
        }
    }

    private final GoalPolicy policy;
    private final LongSupplier nanoClock;
    private final Consumer<String> log;
    private boolean enabled = true;
    private int strikes;
    private long compared;
    private long agreed;
    private long failed;

    public ShadowRunner(GoalPolicy policy, LongSupplier nanoClock, Consumer<String> log) {
        this.policy = policy;
        this.nanoClock = nanoClock;
        this.log = log;
    }

    public ShadowRunner(GoalPolicy policy, Consumer<String> log) {
        this(policy, System::nanoTime, log);
    }

    /**
     * 후보의 판단을 받는다.
     *
     * @param teacherGoal Teacher 가 고른 목표
     * @param autonomous  Teacher 가 스스로 고른 것인지. 사람이 정한 목표는 후보가 맞힐 대상이 아니므로 일치율에 넣지 않는다.
     * @return 기록에 넣을 후보의 판단. 후보가 꺼져 있으면 null
     */
    public @Nullable Experience.ShadowChoice evaluate(Observation observation, GoalType teacherGoal, boolean autonomous) {
        if (!enabled) return null;
        long started = nanoClock.getAsLong();
        GoalPrediction prediction;
        String error = "";
        try {
            prediction = policy.predict(observation);
            if (prediction == null) {
                prediction = GoalPrediction.ABSTAIN;
                error = "policy returned null";
            }
        } catch (RuntimeException | LinkageError e) {
            // 모델을 불러오지 못했거나 입력이 맞지 않는 등 후보 쪽의 어떤 문제도 여기서 멈춘다.
            prediction = GoalPrediction.ABSTAIN;
            error = shorten(e.toString());
        }
        long elapsed = Math.max(0L, nanoClock.getAsLong() - started);

        if (!error.isEmpty()) failed++;
        if (!error.isEmpty() || elapsed > SLOW_NANOS) strike(error.isEmpty() ? "took " + elapsed / 1000 + " us" : error);
        else strikes = 0;

        GoalType predicted = prediction.goal();
        if (autonomous && predicted != null) {
            compared++;
            if (predicted == teacherGoal) agreed++;
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        prediction.scores().forEach((goal, score) -> scores.put(goal.name(), score));
        return new Experience.ShadowChoice(policy.id(), policy.version(), predicted == null ? "" : predicted.name(), scores,
                elapsed / 1000, error);
    }

    public Stats stats() {
        return new Stats(policy.id(), policy.version(), enabled, compared, agreed, failed);
    }

    // 기록에 적는 이름 ("이름@버전")
    public String label() {
        return policy.id() + "@" + policy.version();
    }

    private void strike(String why) {
        if (++strikes < MAX_STRIKES) return;
        enabled = false;
        log.accept("Shadow policy " + label() + " turned off after " + MAX_STRIKES + " bad decisions in a row (last: " + why + ")");
    }

    private static String shorten(String text) {
        return text.length() <= ERROR_LENGTH ? text : text.substring(0, ERROR_LENGTH);
    }
}
