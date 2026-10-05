package me.herry.minecraftAI.ai.policy;

import me.herry.minecraftAI.ai.experience.Experience;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.observation.Observation;
import me.herry.minecraftAI.ai.observation.ObservationFixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 후보 정책을 같이 돌리는 쪽: 답을 받아 두기만 하고, 후보가 말썽을 부려도 밖으로 번지지 않게 한다.
 */
class ShadowRunnerTest {
    private static final Observation OBSERVATION = new ObservationFixture().build();

    private final List<String> log = new ArrayList<>();
    // 시험에서 시간을 직접 움직인다. predict 가 한 번 불릴 때마다 정한 만큼 흐른다.
    private long now;
    private long costNanos = 1000L;

    private ShadowRunner runner(Function<Observation, GoalPrediction> answer) {
        GoalPolicy policy = new GoalPolicy() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public String version() {
                return "7";
            }

            @Override
            public GoalPrediction predict(Observation observation) {
                now += costNanos;
                return answer.apply(observation);
            }
        };
        return new ShadowRunner(policy, () -> now, log::add);
    }

    @Test
    void countsAgreementWithTheTeacher() {
        ShadowRunner runner = runner(o -> new GoalPrediction(GoalType.MINE_IRON, Map.of(GoalType.MINE_IRON, 0.8, GoalType.EXPLORE, 0.2)));
        Experience.ShadowChoice same = runner.evaluate(OBSERVATION, GoalType.MINE_IRON, true);
        Experience.ShadowChoice different = runner.evaluate(OBSERVATION, GoalType.FIND_FOOD, true);

        assertNotNull(same);
        assertEquals("test", same.policy());
        assertEquals("7", same.version());
        assertEquals("MINE_IRON", same.goal());
        assertEquals(0.8, same.scores().get("MINE_IRON"));
        assertEquals(1L, same.latencyMicros());
        assertEquals("", same.error());
        assertNotNull(different);

        ShadowRunner.Stats stats = runner.stats();
        assertEquals(2L, stats.compared());
        assertEquals(1L, stats.agreed());
        assertEquals(0.5, stats.agreement());
        assertEquals("test@7", runner.label());
    }

    // 사람이 정한 목표와, 후보가 답하지 않은 판단은 일치율에 넣지 않는다.
    @Test
    void forcedGoalsAndAbstentionsAreNotCompared() {
        ShadowRunner runner = runner(o -> GoalPrediction.of(GoalType.MINE_IRON));
        runner.evaluate(OBSERVATION, GoalType.MINE_IRON, false);
        assertEquals(0L, runner.stats().compared());

        ShadowRunner silent = runner(o -> GoalPrediction.ABSTAIN);
        Experience.ShadowChoice choice = silent.evaluate(OBSERVATION, GoalType.MINE_IRON, true);
        assertNotNull(choice);
        assertEquals("", choice.goal());
        assertEquals(0L, silent.stats().compared());
        assertEquals(0.0, silent.stats().agreement());
    }

    // 후보가 예외를 내도 부른 쪽으로 나가지 않는다. 답은 "고르지 못함"이 되고 오류 내용이 남는다.
    @Test
    void exceptionsStayInside() {
        ShadowRunner runner = runner(o -> {
            throw new IllegalStateException("model missing");
        });
        Experience.ShadowChoice choice = runner.evaluate(OBSERVATION, GoalType.MINE_IRON, true);
        assertNotNull(choice);
        assertEquals("", choice.goal());
        assertTrue(choice.error().contains("model missing"), choice.error());
        assertEquals(1L, runner.stats().failed());
        assertTrue(runner.stats().enabled());
    }

    @Test
    void nullAnswerCountsAsAnError() {
        ShadowRunner runner = runner(o -> null);
        Experience.ShadowChoice choice = runner.evaluate(OBSERVATION, GoalType.MINE_IRON, true);
        assertNotNull(choice);
        assertEquals("policy returned null", choice.error());
        assertEquals(1L, runner.stats().failed());
    }

    // 오류가 이어지면 꺼진다. 꺼진 뒤에는 후보를 부르지 않는다.
    @Test
    void turnsOffAfterRepeatedFailures() {
        int[] calls = {0};
        ShadowRunner runner = runner(o -> {
            calls[0]++;
            throw new IllegalStateException("broken");
        });
        for (int i = 0; i < ShadowRunner.MAX_STRIKES; i++) assertNotNull(runner.evaluate(OBSERVATION, GoalType.MINE_IRON, true));
        assertFalse(runner.stats().enabled());
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("turned off"), log.get(0));

        assertNull(runner.evaluate(OBSERVATION, GoalType.MINE_IRON, true));
        assertEquals(ShadowRunner.MAX_STRIKES, calls[0]);
    }

    // 느린 판단도 이어지면 끈다. 서버 틱을 계속 잡아먹게 두지 않는다.
    @Test
    void turnsOffWhenItKeepsBeingSlow() {
        costNanos = ShadowRunner.SLOW_NANOS + 1;
        ShadowRunner runner = runner(o -> GoalPrediction.of(GoalType.MINE_IRON));
        for (int i = 0; i < ShadowRunner.MAX_STRIKES; i++) runner.evaluate(OBSERVATION, GoalType.MINE_IRON, true);
        assertFalse(runner.stats().enabled());
        // 느렸어도 답은 답이라서 그동안의 일치는 센다.
        assertEquals(ShadowRunner.MAX_STRIKES, runner.stats().agreed());
    }

    // 가끔 한 번 느린 것으로는 끄지 않는다. 제때 답하면 다시 0 부터 센다.
    @Test
    void anOccasionalSlowAnswerIsForgiven() {
        ShadowRunner runner = runner(o -> GoalPrediction.of(GoalType.MINE_IRON));
        for (int round = 0; round < 3; round++) {
            costNanos = ShadowRunner.SLOW_NANOS + 1;
            for (int i = 0; i < ShadowRunner.MAX_STRIKES - 1; i++) runner.evaluate(OBSERVATION, GoalType.MINE_IRON, true);
            costNanos = 1000L;
            runner.evaluate(OBSERVATION, GoalType.MINE_IRON, true);
        }
        assertTrue(runner.stats().enabled());
        assertTrue(log.isEmpty());
    }

    @Test
    void repeatLastAnswersWithTheCurrentGoal() {
        RepeatLastGoalPolicy policy = new RepeatLastGoalPolicy();
        // 시험용 관측의 "하던 일"은 IDLE 이다.
        assertEquals(GoalType.IDLE, policy.predict(OBSERVATION).goal());
        assertEquals(RepeatLastGoalPolicy.ID, policy.id());
    }

    @Test
    void policiesAreCreatedByName() {
        assertNull(GoalPolicies.create("none"));
        assertNull(GoalPolicies.create("something-else"));
        assertNotNull(GoalPolicies.create("REPEAT-LAST"));
        assertTrue(GoalPolicies.isKnown("Repeat-Last"));
        assertFalse(GoalPolicies.isKnown("onnx"));
    }
}
