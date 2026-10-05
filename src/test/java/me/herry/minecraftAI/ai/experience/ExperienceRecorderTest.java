package me.herry.minecraftAI.ai.experience;

import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.ExploreAreaAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.brain.DecisionTrace;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.LegacyGoalAdapter;
import me.herry.minecraftAI.ai.goal.Stage;
import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.goal.model.GoalMetadata;
import me.herry.minecraftAI.ai.observation.Observation;
import me.herry.minecraftAI.ai.observation.ObservationFixture;
import me.herry.minecraftAI.ai.policy.RepeatLastGoalPolicy;
import me.herry.minecraftAI.ai.policy.ShadowRunner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 판단과 결과를 에피소드로 묶어 남기는 규칙. 파일 대신 목록에 받아서 무엇이 어떤 순서로 남는지 본다.
 */
class ExperienceRecorderTest {
    // 받은 기록을 그대로 쌓아 두는 곳
    private static final class MemorySink implements ExperienceSink {
        final List<Object> records = new ArrayList<>();
        final List<String> ended = new ArrayList<>();

        @Override
        public void append(String ai, String episodeId, Object record) {
            records.add(record);
        }

        @Override
        public void endEpisode(String ai, String episodeId) {
            ended.add(episodeId);
        }

        <T> List<T> of(Class<T> type) {
            return records.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    private static final long WALL = 1_759_660_000_000L;
    private static final Goal MINE = new AcquireGoal("RAW_IRON", 3, GoalMetadata.from(GoalType.MINE_IRON));

    private final MemorySink sink = new MemorySink();
    private final ExperienceRecorder recorder = new ExperienceRecorder("Bot", "1.3.0", sink, () -> WALL, null);

    private static DecisionTrace trace(GoalType goal, DecisionTrace.Origin origin) {
        return new DecisionTrace(100L, goal, 320.0, origin, Stage.IRON_AGE, null,
                List.of(new DecisionTrace.Candidate(goal, 320.0, false), new DecisionTrace.Candidate(GoalType.EXPLORE, 10.0, true)),
                "reason", GoalType.IDLE);
    }

    private static Observation observation(int rawIron) {
        return new ObservationFixture().item("RAW_IRON", rawIron).build();
    }

    @Test
    void nothingIsRecordedOutsideAnEpisode() {
        recorder.observed(observation(0));
        recorder.decision(100L, observation(0), trace(GoalType.MINE_IRON, DecisionTrace.Origin.AUTONOMOUS), MINE, "MINE_IRON", false, List.of());
        recorder.outcome(120L, Experience.Outcome.SUCCEEDED, "");
        recorder.end(Experience.EndReason.STOPPED, 130L);
        assertTrue(sink.records.isEmpty());
        assertFalse(recorder.isRecording());
        assertNull(recorder.episodeId());
    }

    @Test
    void episodeLinksDecisionActionsAndOutcome() {
        recorder.begin(Experience.StartReason.START, 0L, 42L);
        assertTrue(recorder.isRecording());
        Observation before = observation(1);
        recorder.observed(before);
        Action wait = new WaitAction(5);
        List<Action> plan = List.of(wait, new ExploreAreaAction(5.0, 10.0));
        recorder.decision(100L, before, trace(GoalType.MINE_IRON, DecisionTrace.Origin.AUTONOMOUS), MINE, "MINE_IRON", false, plan);
        recorder.action(0, wait, 100L, 106L);
        recorder.outcome(140L, Experience.Outcome.SUCCEEDED, "");
        // 결과는 그 뒤의 첫 관측이 와야 남는다. 그 관측으로 완료 조건을 본다.
        assertTrue(sink.of(Experience.PlanOutcome.class).isEmpty());
        ObservationFixture after = new ObservationFixture().item("RAW_IRON", 3);
        after.tick = 145L;
        recorder.observed(after.build());
        recorder.end(Experience.EndReason.STOPPED, 200L);

        Experience.EpisodeStart start = assertInstanceOf(Experience.EpisodeStart.class, sink.records.get(0));
        assertEquals("episode_start", start.type());
        assertEquals(Experience.SCHEMA_VERSION, start.schemaVersion());
        assertEquals(Observation.SCHEMA_VERSION, start.observationVersion());
        assertEquals("1.3.0", start.pluginVersion());
        assertEquals(42L, start.worldSeed());
        assertEquals(WALL, start.wallTimeMs());
        assertEquals("", start.shadowPolicy());
        assertTrue(start.episodeId().startsWith("bot-"), start.episodeId());

        Experience.Decision decision = sink.of(Experience.Decision.class).get(0);
        assertEquals(start.episodeId(), decision.episodeId());
        assertEquals(0L, decision.decisionId());
        assertEquals(before, decision.observation());
        assertEquals("MINE_IRON", decision.teacher().goal());
        assertEquals("AUTONOMOUS", decision.teacher().origin());
        assertEquals(2, decision.teacher().candidates().size());
        assertTrue(decision.teacher().candidates().get(1).resting());
        assertNull(decision.shadow());
        assertEquals("MINE_IRON", decision.executed().goal());
        assertEquals("Acquire(RAW_IRON x3)", decision.executed().goalSpec());
        assertEquals("ACQUIRE", decision.executed().goalCategory());
        // 기본 행동은 종류와 대상이 남고, 복합 행동은 이름만 남는다.
        assertEquals("WAIT", decision.executed().actions().get(0).primitive());
        assertEquals("", decision.executed().actions().get(1).primitive());
        assertEquals("ExploreArea", decision.executed().actions().get(1).name());

        Experience.ActionResult action = sink.of(Experience.ActionResult.class).get(0);
        assertEquals(0L, action.decisionId());
        assertEquals("Wait", action.name());
        assertEquals(100L, action.startTick());
        assertEquals(106L, action.endTick());

        // 계획이 끝난 뒤의 관측으로 보면 철 원석 3개를 가졌다.
        Experience.PlanOutcome outcome = sink.of(Experience.PlanOutcome.class).get(0);
        assertEquals(Experience.Outcome.SUCCEEDED, outcome.outcome());
        assertEquals(140L, outcome.tick());
        assertTrue(outcome.goalAchieved());
        assertEquals(145L, outcome.judgedAtTick());

        Experience.EpisodeEnd end = assertInstanceOf(Experience.EpisodeEnd.class, sink.records.get(sink.records.size() - 1));
        assertEquals(Experience.EndReason.STOPPED, end.reason());
        assertEquals(1L, end.decisions());
        assertNotNull(end.finalObservation());
        assertEquals(List.of(start.episodeId()), sink.ended);
        assertFalse(recorder.isRecording());
    }

    // 하던 계획이 있는 채로 죽으면, 그 계획은 "하던 중에 그만둠"으로 닫히고 에피소드는 죽음으로 끝난다.
    @Test
    void deathClosesTheOpenPlanFirst() {
        recorder.begin(Experience.StartReason.START, 0L, 1L);
        recorder.observed(observation(0));
        recorder.decision(10L, observation(0), trace(GoalType.MINE_IRON, DecisionTrace.Origin.AUTONOMOUS), MINE, "MINE_IRON", false, List.of());
        recorder.end(Experience.EndReason.DEATH, 50L);

        Experience.PlanOutcome outcome = sink.of(Experience.PlanOutcome.class).get(0);
        assertEquals(Experience.Outcome.INTERRUPTED, outcome.outcome());
        assertEquals("episode ended: DEATH", outcome.detail());
        assertFalse(outcome.goalAchieved());
        assertEquals(Experience.EndReason.DEATH, sink.of(Experience.EpisodeEnd.class).get(0).reason());
        // 끝난 뒤에 늦게 들어온 것은 남기지 않는다.
        recorder.outcome(60L, Experience.Outcome.FAILED, "late");
        assertEquals(1, sink.of(Experience.PlanOutcome.class).size());
    }

    // 되살아나면 새 에피소드다. ID 가 달라야 파일도 따로 생긴다.
    @Test
    void respawnStartsANewEpisode() {
        recorder.begin(Experience.StartReason.START, 0L, 1L);
        String first = recorder.episodeId();
        // 이미 열려 있으면 다시 시작하지 않는다.
        recorder.begin(Experience.StartReason.START, 5L, 1L);
        assertEquals(first, recorder.episodeId());
        recorder.end(Experience.EndReason.DEATH, 50L);
        recorder.begin(Experience.StartReason.RESPAWN, 60L, 1L);
        assertNotEquals(first, recorder.episodeId());
        assertEquals(2, sink.of(Experience.EpisodeStart.class).size());
        assertEquals(Experience.StartReason.RESPAWN, sink.of(Experience.EpisodeStart.class).get(1).reason());
        assertEquals(60L, sink.of(Experience.EpisodeStart.class).get(1).aiTicks());
    }

    // 판단 번호는 에피소드 안에서 0 부터 하나씩 늘고, 계획이 끝나지 않은 채 새 판단이 오면 앞의 것을 닫는다.
    @Test
    void decisionIdsCountUpAndAnUnfinishedPlanIsClosed() {
        recorder.begin(Experience.StartReason.START, 0L, 1L);
        recorder.observed(observation(0));
        DecisionTrace trace = trace(GoalType.MINE_IRON, DecisionTrace.Origin.AUTONOMOUS);
        recorder.decision(10L, observation(0), trace, MINE, "MINE_IRON", false, List.of());
        recorder.decision(20L, observation(0), trace, MINE, "MINE_IRON", false, List.of());
        recorder.outcome(30L, Experience.Outcome.NO_PLAN, "no plan");
        recorder.decision(40L, observation(0), trace, MINE, "DigWayOut", true, List.of());

        assertEquals(List.of(0L, 1L, 2L), sink.of(Experience.Decision.class).stream().map(Experience.Decision::decisionId).toList());
        List<Experience.PlanOutcome> outcomes = sink.of(Experience.PlanOutcome.class);
        assertEquals(0L, outcomes.get(0).decisionId());
        assertEquals(Experience.Outcome.INTERRUPTED, outcomes.get(0).outcome());
        assertEquals(1L, outcomes.get(1).decisionId());
        assertEquals(Experience.Outcome.NO_PLAN, outcomes.get(1).outcome());
        assertTrue(sink.of(Experience.Decision.class).get(2).executed().escaping());
        assertEquals(3L, recorder.decisionCount());
    }

    // 관측을 남기지 못한 판단은 학습에 쓸 수 없으므로 남기지 않는다.
    @Test
    void decisionWithoutObservationIsSkipped() {
        recorder.begin(Experience.StartReason.START, 0L, 1L);
        recorder.decision(10L, null, trace(GoalType.MINE_IRON, DecisionTrace.Origin.AUTONOMOUS), MINE, "MINE_IRON", false, List.of());
        assertTrue(sink.of(Experience.Decision.class).isEmpty());
    }

    @Test
    void newMilestonesAndStagesBecomeEvents() {
        recorder.begin(Experience.StartReason.START, 0L, 1L);
        ObservationFixture fixture = new ObservationFixture();
        recorder.observed(fixture.build());
        // 처음 본 상태는 기준일 뿐이라 사건이 아니다.
        assertTrue(sink.of(Experience.Event.class).isEmpty());

        fixture.achieved.add("CRAFTING_TABLE");
        fixture.stage = "IRON_AGE";
        recorder.observed(fixture.build());
        recorder.observed(fixture.build());
        List<Experience.Event> events = sink.of(Experience.Event.class);
        assertEquals(2, events.size());
        assertEquals("MILESTONE", events.get(0).kind());
        assertEquals("CRAFTING_TABLE", events.get(0).value());
        assertEquals("STAGE", events.get(1).kind());
        assertEquals("IRON_AGE", events.get(1).value());
    }

    // 최종 목표를 이루면 에피소드가 "이룸"으로 끝난다.
    @Test
    void defeatingTheDragonCompletesTheEpisode() {
        recorder.begin(Experience.StartReason.START, 0L, 1L);
        ObservationFixture fixture = new ObservationFixture();
        recorder.observed(fixture.build());
        fixture.dragonDefeated = true;
        recorder.observed(fixture.build());
        assertEquals(Experience.EndReason.COMPLETED, sink.of(Experience.EpisodeEnd.class).get(0).reason());
        assertFalse(recorder.isRecording());
    }

    // 후보 정책의 답은 Teacher 의 것과 따로 남고, 기록을 꺼 두어도 후보는 돌려서 일치율을 센다.
    @Test
    void shadowChoiceIsRecordedBesideTheTeacher() {
        ShadowRunner shadow = new ShadowRunner(new RepeatLastGoalPolicy(), message -> { });
        ExperienceRecorder withShadow = new ExperienceRecorder("Bot", "1.3.0", sink, () -> WALL, shadow);
        Goal explore = LegacyGoalAdapter.toGoal(GoalType.EXPLORE);

        // 에피소드 밖: 남기지는 않지만 견주기는 한다. 하던 일(IDLE)과 Teacher 의 선택(EXPLORE)이 다르다.
        withShadow.decision(10L, observation(0), trace(GoalType.EXPLORE, DecisionTrace.Origin.AUTONOMOUS), explore, "EXPLORE", false, List.of());
        assertTrue(sink.records.isEmpty());
        assertEquals(1L, shadow.stats().compared());
        assertEquals(0L, shadow.stats().agreed());

        withShadow.begin(Experience.StartReason.START, 0L, 1L);
        assertEquals("repeat-last@1", sink.of(Experience.EpisodeStart.class).get(0).shadowPolicy());
        withShadow.decision(20L, observation(0), trace(GoalType.IDLE, DecisionTrace.Origin.AUTONOMOUS), explore, "IDLE", false, List.of());
        Experience.Decision decision = sink.of(Experience.Decision.class).get(0);
        assertNotNull(decision.shadow());
        assertEquals("repeat-last", decision.shadow().policy());
        assertEquals("IDLE", decision.shadow().goal());
        assertEquals("IDLE", decision.teacher().goal());
        assertEquals(1L, shadow.stats().agreed());

        // 사람이 정한 목표는 후보가 맞힐 대상이 아니라서 일치율에 넣지 않는다.
        withShadow.decision(30L, observation(0), trace(GoalType.EXPLORE, DecisionTrace.Origin.FORCED), explore, "EXPLORE", false, List.of());
        assertEquals(2L, shadow.stats().compared());
    }
}
