package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.brain.DecisionTrace;
import me.herry.minecraftAI.ai.experience.Experience;
import me.herry.minecraftAI.ai.experience.ExperienceRecorder;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.LegacyGoalAdapter;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.observation.CurrentTaskState;
import me.herry.minecraftAI.ai.observation.Observation;
import me.herry.minecraftAI.ai.observation.ObservationCapture;
import me.herry.minecraftAI.ai.policy.GoalPolicies;
import me.herry.minecraftAI.ai.policy.GoalPolicy;
import me.herry.minecraftAI.ai.policy.ShadowRunner;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 판단 루프(AIBrain) 곁에서 "무엇을 알고, 무엇을 하기로 했고, 어떻게 끝났는지"를 적어 두는 곳.
 * 지금 목표를 "무엇을 얼마나"로 적은 것, 계획을 세운 스킬, 마지막 관측을 들고 있고, 같은 내용을 학습용 기록으로 넘긴다.
 *
 * 여기서 하는 일은 판단에 쓰이지 않는다. 적지 못하는 일이 생겨도 AI 는 그대로 움직인다.
 */
public final class DecisionJournal {
    private final AIPlayer ai;
    private final ExperienceRecorder recorder;

    private Goal goalSpec = LegacyGoalAdapter.toGoal(GoalType.IDLE);
    private String skill = "";
    // goalSpec 의 완료 조건이 이미 채워졌다고 알렸는지
    private boolean specReached;
    private @Nullable Observation observation;
    // 지금 실행 중인 행동이 시작된 틱
    private long actionStartedAt;

    DecisionJournal(AIPlayer ai, ExperienceRecorder recorder) {
        this.ai = ai;
        this.recorder = recorder;
    }

    // 설정에 따라 기록할 곳과 같이 돌릴 후보 정책을 붙인다. 둘 다 꺼져 있으면 아무것도 남기지 않는다.
    static DecisionJournal create(AIPlayer ai, AIServices services) {
        GoalPolicy candidate = GoalPolicies.create(services.config().shadowPolicy);
        ShadowRunner shadow = candidate == null ? null : new ShadowRunner(candidate, services.warn());
        return new DecisionJournal(ai, new ExperienceRecorder(ai.getName(), services.pluginVersion(), services.experience(),
                System::currentTimeMillis, shadow));
    }

    // 지금 목표를 "무엇을 얼마나"로 적은 것. 계획을 세울 때마다 그때의 상황으로 다시 적는다.
    public Goal goalSpec() {
        return goalSpec;
    }

    // 지금 계획을 세운 스킬의 이름. 계획이 없으면 빈 문자열.
    public String skill() {
        return skill;
    }

    // 마지막으로 판단할 때의 관측. 아직 한 번도 판단하지 않았으면 null.
    public @Nullable Observation observation() {
        return observation;
    }

    public ExperienceRecorder recorder() {
        return recorder;
    }

    // 진행 중인 것을 모두 버리고 처음 상태로 돌아갈 때.
    void reset() {
        goalSpec = LegacyGoalAdapter.toGoal(GoalType.IDLE);
        skill = "";
        specReached = false;
    }

    void beginEpisode(Experience.StartReason reason) {
        recorder.begin(reason, ai.getTicks(), ai.getPlayer().getWorld().getSeed());
    }

    void endEpisode(Experience.EndReason reason) {
        recorder.end(reason, ai.getTicks());
    }

    /**
     * 이번 판단의 관측을 남기고, 하던 목표의 완료 조건이 채워졌는지 본다.
     * 완료 조건은 알리기만 한다. 목표를 바꾸는 것은 지금까지처럼 GoalSystem 의 점수가 정한다.
     *
     * @param task 이 판단을 하기 직전에 하고 있던 일 (목표 명세와 스킬은 여기서 채운다)
     */
    void observe(Situation situation, GoalType goal, String action, String plan, boolean escaping, int restCount) {
        CurrentTaskState task = new CurrentTaskState(goal.name(), goalSpec.describe(), skill, action, plan, escaping, restCount);
        Observation captured;
        try {
            captured = ObservationCapture.capture(ai, situation, task);
        } catch (RuntimeException e) {
            // 관측을 남기지 못했다고 AI 를 세우지 않고, 이번 것만 건너뛴다.
            ai.debug("Observation failed: " + e);
            return;
        }
        observation = captured;
        if (!specReached && goalSpec.isAchieved(captured)) {
            specReached = true;
            ai.debug("Goal state reached: " + goalSpec.describe());
        }
        recorder.observed(captured);
    }

    /**
     * 새 계획을 세운 판단을 적는다. 목표 명세나 스킬이 달라졌을 때만 로그로 알린다.
     *
     * @param trace   이 판단의 근거 (고른 목표, 점수, 다른 후보). 아직 없으면 학습용 기록은 남기지 않는다.
     * @param actions 세운 계획. 세우지 못해서 기다리기만 하는 경우에도 그 행동을 넣는다.
     */
    void decided(long now, @Nullable DecisionTrace trace, Goal spec, String planSkill, boolean escaping, List<Action> actions) {
        if (!spec.equals(goalSpec) || !planSkill.equals(skill)) {
            if (!spec.equals(goalSpec)) {
                goalSpec = spec;
                // 처음부터 채워져 있던 조건은 "이뤘다"고 알리지 않는다. 하는 동안에 채워졌을 때만 알린다.
                specReached = observation != null && spec.isAchieved(observation);
            }
            skill = planSkill;
            ai.debug("Goal spec: " + spec.describe() + (planSkill.isEmpty() ? "" : " via " + planSkill));
        }
        if (trace != null) recorder.decision(now, observation, trace, spec, planSkill, escaping, actions);
    }

    void actionStarted(long now) {
        actionStartedAt = now;
    }

    // 행동 하나가 성공하거나 실패해서 끝났다.
    void actionFinished(int index, Action action, long now) {
        recorder.action(index, action, actionStartedAt, now);
    }

    void planEnded(long now, Experience.Outcome outcome, String detail) {
        recorder.outcome(now, outcome, detail);
    }
}
