package me.herry.minecraftAI.ai.experience;

import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.brain.DecisionTrace;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.observation.Observation;
import me.herry.minecraftAI.ai.policy.ShadowRunner;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * AI 한 명의 판단과 그 결과를 에피소드 단위로 남긴다. 무엇을 언제 남길지만 정하고, 파일로 쓰는 일은 ExperienceSink 가 한다.
 * 서버 메인 스레드에서만 부른다. Bukkit 에 의존하지 않아서 서버 없이 시험할 수 있다.
 *
 * 후보 정책(ShadowRunner)이 있으면 판단마다 같은 관측으로 돌려서 그 답을 함께 남긴다.
 * 기록을 꺼 두어도 후보는 돌리므로 일치율은 볼 수 있다.
 */
public final class ExperienceRecorder {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final String EVENT_MILESTONE = "MILESTONE";
    private static final String EVENT_STAGE = "STAGE";

    private final String ai;
    private final String pluginVersion;
    private final ExperienceSink sink;
    private final LongSupplier wallClock;
    private final @Nullable ShadowRunner shadow;

    private @Nullable String episodeId;
    private int episodes;
    private long decisions;
    private long totalDecisions;
    // 아직 끝나지 않은 판단과 그 목표. 없으면 -1.
    private long openDecision = -1L;
    private @Nullable Goal openGoal;
    // 끝났지만 아직 쓰지 않은 계획의 결과. 그 뒤의 첫 관측으로 완료 조건을 보고 나서 쓴다.
    private @Nullable Experience.PlanOutcome pendingOutcome;
    private @Nullable Goal pendingGoal;
    private @Nullable Observation latest;

    /**
     * @param wallClock 실제 시각 (1970년부터의 밀리초)
     * @param shadow    같이 돌릴 후보 정책. 없으면 null
     */
    public ExperienceRecorder(String ai, String pluginVersion, ExperienceSink sink, LongSupplier wallClock, @Nullable ShadowRunner shadow) {
        this.ai = ai;
        this.pluginVersion = pluginVersion;
        this.sink = sink;
        this.wallClock = wallClock;
        this.shadow = shadow;
    }

    // 파일로 남기는 중인지
    public boolean isRecording() {
        return sink != ExperienceSink.NONE && episodeId != null;
    }

    public @Nullable String episodeId() {
        return episodeId;
    }

    // 지금까지 남긴 판단의 수 (모든 에피소드)
    public long decisionCount() {
        return totalDecisions;
    }

    public @Nullable ShadowRunner shadow() {
        return shadow;
    }

    /**
     * 에피소드를 시작한다. 이미 열려 있으면 아무것도 하지 않는다.
     */
    public void begin(Experience.StartReason reason, long aiTicks, long worldSeed) {
        if (episodeId != null) return;
        long now = wallClock.getAsLong();
        episodes++;
        episodeId = ai.toLowerCase(Locale.ROOT) + "-" + STAMP.format(Instant.ofEpochMilli(now)) + "-" + String.format(Locale.ROOT, "%03d", episodes);
        decisions = 0;
        openDecision = -1L;
        openGoal = null;
        pendingOutcome = null;
        pendingGoal = null;
        latest = null;
        write(new Experience.EpisodeStart("episode_start", Experience.SCHEMA_VERSION, Observation.SCHEMA_VERSION, episodeId, ai,
                pluginVersion, reason, aiTicks, now, worldSeed, shadow == null ? "" : shadow.label()));
    }

    /**
     * 에피소드를 끝낸다. 하던 계획이 있었으면 "하던 중에 그만둠"으로 먼저 닫는다. 열려 있지 않으면 아무것도 하지 않는다.
     */
    public void end(Experience.EndReason reason, long aiTicks) {
        if (episodeId == null) return;
        if (openDecision >= 0) outcome(aiTicks, Experience.Outcome.INTERRUPTED, "episode ended: " + reason);
        flushOutcome();
        write(new Experience.EpisodeEnd("episode_end", episodeId, reason, aiTicks, wallClock.getAsLong(), decisions, latest));
        sink.endEpisode(ai, episodeId);
        episodeId = null;
    }

    /**
     * 판단할 때마다 그때의 관측을 받는다. 새로 이룬 항목과 단계 변화를 사건으로 남기고, 최종 목표를 이뤘으면 에피소드를 끝낸다.
     */
    public void observed(Observation observation) {
        Observation before = latest;
        latest = observation;
        if (episodeId == null) return;
        flushOutcome();
        if (before != null) {
            for (String milestone : observation.progress().achievedMilestones()) {
                if (!before.progress().achievedMilestones().contains(milestone)) event(observation.tick(), EVENT_MILESTONE, milestone);
            }
            if (!observation.progress().stage().equals(before.progress().stage())) {
                event(observation.tick(), EVENT_STAGE, observation.progress().stage());
            }
        }
        if (observation.progress().dragonDefeated()) end(Experience.EndReason.COMPLETED, observation.tick());
    }

    /**
     * 새 계획을 세운 판단을 남긴다.
     *
     * @param observation 이 판단에 쓴 관측. 남기지 못했으면(null) 판단도 남기지 않는다.
     * @param trace       Teacher 의 판단 (고른 목표, 점수, 다른 후보)
     * @param goal        실행하기로 한 목표를 "무엇을 얼마나"로 적은 것
     * @param skill       계획을 세운 스킬. 세우지 못했으면 빈 문자열
     * @param actions     세운 계획의 행동들
     */
    public void decision(long tick, @Nullable Observation observation, DecisionTrace trace, Goal goal, String skill, boolean escaping,
                         List<Action> actions) {
        if (observation == null) return;
        Experience.ShadowChoice shadowChoice = shadow == null ? null
                : shadow.evaluate(observation, trace.goal(), trace.origin() == DecisionTrace.Origin.AUTONOMOUS);
        if (episodeId == null) return;
        if (openDecision >= 0) outcome(tick, Experience.Outcome.INTERRUPTED, "replaced by a new plan");
        flushOutcome();

        List<Experience.Candidate> candidates = new ArrayList<>();
        for (DecisionTrace.Candidate candidate : trace.candidates()) {
            candidates.add(new Experience.Candidate(candidate.goal().name(), candidate.score(), candidate.resting()));
        }
        Experience.TeacherChoice teacher = new Experience.TeacherChoice(trace.goal().name(), trace.score(), trace.origin().name(), candidates);

        List<Experience.PlannedAction> planned = new ArrayList<>();
        for (Action action : actions) {
            if (action instanceof PrimitiveAction primitive) {
                planned.add(new Experience.PlannedAction(action.getName(), primitive.getPrimitiveType().name(), primitive.getTarget()));
            } else {
                planned.add(new Experience.PlannedAction(action.getName(), "", PrimitiveTarget.NONE));
            }
        }
        GoalType legacy = goal.metadata().legacyOrigin();
        Experience.Executed executed = new Experience.Executed(legacy == null ? "" : legacy.name(), goal.describe(),
                goal.category().name(), skill, escaping, planned);

        openDecision = decisions++;
        openGoal = goal;
        totalDecisions++;
        write(new Experience.Decision("decision", episodeId, openDecision, tick, observation, teacher, shadowChoice, executed));
    }

    // 계획의 행동 하나가 끝났다 (성공 또는 실패).
    public void action(int index, Action action, long startTick, long endTick) {
        if (episodeId == null || openDecision < 0) return;
        write(new Experience.ActionResult("action", episodeId, openDecision, index, action.getName(), startTick, endTick,
                action.getStatus().name(), action.getFailReason()));
    }

    /**
     * 계획이 끝났다. 열려 있는 판단이 없으면 아무것도 하지 않는다.
     * 기록은 바로 쓰지 않고, 그 뒤의 첫 관측이 오면 그것으로 목표의 완료 조건을 본 다음에 쓴다
     * (끝난 순간에는 아직 그 결과가 반영된 관측이 없다).
     */
    public void outcome(long tick, Experience.Outcome outcome, String detail) {
        if (episodeId == null || openDecision < 0) return;
        flushOutcome();
        pendingOutcome = new Experience.PlanOutcome("outcome", episodeId, openDecision, tick, outcome, detail, false, -1L);
        pendingGoal = openGoal;
        openDecision = -1L;
        openGoal = null;
    }

    // 미뤄 둔 계획의 결과를 지금 가진 가장 새 관측으로 판정해서 쓴다.
    private void flushOutcome() {
        Experience.PlanOutcome pending = pendingOutcome;
        if (pending == null) return;
        boolean achieved = pendingGoal != null && latest != null && pendingGoal.isAchieved(latest);
        write(new Experience.PlanOutcome(pending.type(), pending.episodeId(), pending.decisionId(), pending.tick(), pending.outcome(),
                pending.detail(), achieved, latest == null ? -1L : latest.tick()));
        pendingOutcome = null;
        pendingGoal = null;
    }

    private void event(long tick, String kind, String value) {
        write(new Experience.Event("event", episodeId, tick, kind, value));
    }

    private void write(Object record) {
        sink.append(ai, episodeId, record);
    }
}
