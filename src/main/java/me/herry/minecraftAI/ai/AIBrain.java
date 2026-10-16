package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.ActionStatus;
import me.herry.minecraftAI.ai.action.RunAwayAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.brain.DecisionLog;
import me.herry.minecraftAI.ai.brain.DecisionTrace;
import me.herry.minecraftAI.ai.brain.Directive;
import me.herry.minecraftAI.ai.brain.GoalReasons;
import me.herry.minecraftAI.ai.brain.RecoveryTracker;
import me.herry.minecraftAI.ai.experience.Experience;
import me.herry.minecraftAI.ai.goal.GoalSystem;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.LegacyGoalAdapter;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.perf.TickProfiler;
import me.herry.minecraftAI.ai.plan.BuildPlans;
import me.herry.minecraftAI.ai.plan.Plan;
import me.herry.minecraftAI.ai.plan.Planner;
import me.herry.minecraftAI.ai.skill.SkillPlan;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveProgram;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveResult;
import me.herry.minecraftAI.ai.primitive.control.Submission;
import me.herry.minecraftAI.ai.primitive.runtime.PrimitiveProgramAction;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AI 의 판단 루프.
 * 인식 -> 상황 요약 -> 목표 선택 -> 계획 -> 행동 실행 -> 결과 확인 -> (실패하면 회복) -> 재계획 을 반복한다.
 * 무거운 판단은 정해진 주기마다만 하고, 매 틱에는 진행 중인 행동 하나만 이어서 실행한다.
 *
 * 무엇을 향해 가는지(최종/장기/중기 목표)는 Progression 이 정하고, 여기서는 그것을 위해 지금 할 단기 목표를 고른다.
 * 고른 이유와 실패는 DecisionLog 에 남겨서 사람이 물었을 때 설명할 수 있게 한다.
 */
final class AIBrain {
    private static final long MEMORY_PRUNE_INTERVAL = 200L;
    private static final long PERCEPTION_LOG_INTERVAL = 100L;
    // 더 좋은 갑옷을 얻었는지 확인하는 주기
    private static final long EQUIP_CHECK_INTERVAL = 40L;
    // 계획을 세울 수 없을 때 다시 시도하기 전까지 기다리는 시간. 매 판단마다 계획을 다시 세우는 것을 막는다.
    private static final int NO_PLAN_WAIT_TICKS = 20;
    private static final int TRAPPED_THRESHOLD = 2;
    private static final int TRACE_CANDIDATES = 5;
    // 사람이 부탁한 일은 긴급 목표 바로 아래의 우선순위로 한다. 위험 탈출이나 전투가 생기면 그쪽이 먼저다.
    private static final double DIRECTIVE_SCORE = GoalSystem.EMERGENCY_SCORE - 1.0;
    // 없어도 되는 중기 목표(집 등)가 이만큼 거듭 막히면 한동안 미뤄 두고 다음 단계로 넘어간다.
    private static final int DEFER_AFTER_RESTS = 3;
    private static final long DEFER_TICKS = 12000L;
    // 쉬게 한 목표는, 그 자리에서 이만큼 떨어진 곳으로 옮기면 다시 시도한다.
    private static final double REST_RELEASE_DISTANCE = 12.0;
    private static final String NO_PLAN = "(계획 없음)";
    // 갇힌 곳에서 길을 파서 나오는 계획은 어느 스킬의 것도 아니라서 이 이름으로 적는다.
    private static final String DIG_WAY_OUT = "DigWayOut";

    private record Choice(GoalType goal, double score, DecisionTrace.Origin origin) {
    }

    private final AIPlayer ai;
    private final Planner planner;
    private final GoalSystem goals = new GoalSystem();
    private final RecoveryTracker recovery = new RecoveryTracker();
    private final DecisionLog log = new DecisionLog();
    // 목표 명세, 스킬, 관측, 학습용 기록. 판단에는 쓰지 않는다.
    private final DecisionJournal journal;
    // 쉬게 한 목표와, 쉬게 할 때 서 있던 자리
    private final Map<GoalType, BlockPoint> restedAt = new EnumMap<>(GoalType.class);

    private GoalType currentGoal = GoalType.IDLE;
    private @Nullable GoalType previousGoal;
    private @Nullable DecisionTrace activeTrace;
    private @Nullable GoalType forcedGoal;
    private @Nullable Directive directive;
    private @Nullable Situation lastSituation;
    private @Nullable Plan plan;
    private @Nullable PrimitiveProgramAction controlledProgram;
    private @Nullable PrimitiveResult lastPrimitiveResult;
    private boolean primitiveLease;
    private int primitiveIdleTicks;
    private long primitiveIdleUntil;
    private java.util.UUID primitiveLeaseWorld;
    private boolean waitingForPlan;
    // "갇혀서 갈 수 없음"으로 이동이 연달아 실패한 횟수
    private int trappedStreak;
    private boolean escaping;

    AIBrain(AIPlayer ai, AIServices services) {
        this.ai = ai;
        this.planner = services.planner();
        this.journal = DecisionJournal.create(ai, services);
    }

    void tick() {
        TickProfiler profiler = ai.getProfiler();
        long tickStarted = profiler.begin();
        long now = ai.getTicks();

        if (primitiveLease && controlledProgram == null && (now >= primitiveIdleUntil
                || !ai.getWorldId().equals(primitiveLeaseWorld) || ai.getPlayer().getHealth() <= 6.0
                || ai.getPlayer().isInLava()
                || ai.getPlayer().isInWater() && ai.getPlayer().getRemainingAir() <= 60)) {
            cancelPrimitiveProgram("idle control expired or became unsafe", true);
        }

        long started = profiler.begin();
        boolean scanned = ai.getPerceptionSystem().tickBlockScan(ai.getMemory(), now);
        profiler.end(TickProfiler.Section.BLOCK_SCAN, started);
        if (scanned) {
            ai.debug("Block scan finished: " + ai.getPerception().getNearbyBlockCount() + " blocks of interest");
            // 새로 훑어본 결과를 근처의 동료와 나눈다 (동료가 있을 때만).
            ai.getTeam().shareDiscoveries(ai);
        }
        if (now % ai.getConfig().updateInterval == 0) {
            if (controlledProgram == null && !primitiveLease) think(now);
            else ai.getPerceptionSystem().update();
        }

        started = profiler.begin();
        runAction(now);
        profiler.end(TickProfiler.Section.ACTION, started);
        profiler.end(TickProfiler.Section.TOTAL, tickStarted);
        profiler.endTick();
    }

    // 진행 중인 행동과 계획을 모두 버리고 처음 상태로 돌아간다.
    void reset() {
        cancelPrimitiveProgram("lifecycle reset", false);
        if (plan != null) plan.cancel(ai);
        plan = null;
        trappedStreak = 0;
        currentGoal = GoalType.IDLE;
        journal.reset();
        goals.clearCooldowns();
        recovery.reset();
        restedAt.clear();
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
    }

    // 닿지 못해서 포기했던 상대라도 먼저 때려 오면 다시 맞서 싸울 수 있게 한다.
    void onAttacked() {
        // Damage events arrive before health is applied; capture its actual effect on the next tick.
        if (controlledProgram != null) controlledProgram.requestInterrupt("attacked");
        else if (primitiveLease) cancelPrimitiveProgram("attacked while awaiting policy", true);
        goals.clearCooldown(GoalType.FIGHT_HOSTILE);
    }

    Submission submitPrimitiveProgram(PrimitiveProgram program) {
        if (controlledProgram != null) return new Submission(false, "another primitive program is running");
        if (forcedGoal != null || directive != null) return new Submission(false, "an explicit goal or directive is active");
        if (!program.worldId().equals(ai.getWorldId())) return new Submission(false, "world mismatch");
        long age = ai.getTicks() - program.observedTick();
        if (age < 0 || age > 40) return new Submission(false, "stale or future observation");
        if (ai.getPlayer().getHealth() <= 6.0 || ai.getPlayer().isInLava()) return new Submission(false, "unsafe player state");
        if (!primitiveLease) interruptTeacherForPrimitiveControl();
        controlledProgram = new PrimitiveProgramAction(program);
        lastPrimitiveResult = null;
        plan = new Plan(GoalType.IDLE, List.of(controlledProgram));
        return new Submission(true, "");
    }

    Submission beginPrimitiveControl(int idleTicks) {
        if (idleTicks < 1 || idleTicks > 100) return new Submission(false, "idle budget must be 1..100 ticks");
        if (primitiveLease || controlledProgram != null) return new Submission(false, "primitive control is already active");
        if (forcedGoal != null || directive != null) return new Submission(false, "an explicit goal or directive is active");
        if (ai.getPlayer().getHealth() <= 6.0 || ai.getPlayer().isInLava()) return new Submission(false, "unsafe player state");
        interruptTeacherForPrimitiveControl();
        primitiveLease = true;
        primitiveIdleTicks = idleTicks;
        primitiveIdleUntil = ai.getTicks() + idleTicks;
        primitiveLeaseWorld = ai.getWorldId();
        return new Submission(true, "");
    }

    boolean hasPrimitiveControl() { return primitiveLease || controlledProgram != null; }

    private void interruptTeacherForPrimitiveControl() {
        if (plan != null) plan.cancel(ai);
        journal.planEnded(ai.getTicks(), Experience.Outcome.INTERRUPTED, "explicit primitive control");
        // Prepared/external control is never appended to an autonomous Teacher episode.
        journal.endEpisode(Experience.EndReason.STOPPED);
        journal.reset();
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
        activeTrace = null;
        currentGoal = GoalType.IDLE;
        plan = null;
    }

    @Nullable PrimitiveResult primitiveResult() {
        return controlledProgram == null ? lastPrimitiveResult : controlledProgram.result();
    }

    boolean cancelPrimitiveProgram(String reason, boolean resumeJournal) {
        if (controlledProgram == null && !primitiveLease) return false;
        if (controlledProgram != null) {
            controlledProgram.cancel(ai, reason);
            lastPrimitiveResult = controlledProgram.result();
        }
        controlledProgram = null;
        primitiveLease = false;
        primitiveLeaseWorld = null;
        plan = null;
        if (resumeJournal) journal.beginEpisode(Experience.StartReason.START);
        return true;
    }

    GoalType getCurrentGoal() {
        return currentGoal;
    }

    boolean isEscaping() {
        return escaping;
    }

    DecisionJournal getJournal() {
        return journal;
    }

    @Nullable GoalType getForcedGoal() {
        return forcedGoal;
    }

    void setForcedGoal(@Nullable GoalType goal) {
        cancelPrimitiveProgram("forced goal changed", true);
        forcedGoal = goal;
        goals.clearCooldowns();
        recovery.reset();
        ai.debug(goal == null ? "Forced goal cleared" : "Forced goal: " + goal);
    }

    @Nullable Directive getDirective() {
        return directive;
    }

    /**
     * 사람이 부탁한 일을 맡는다. null 이면 부탁을 취소하고 스스로 판단한다.
     * 위험을 피하거나 싸우는 중이 아니면 하던 계획을 접고 바로 부탁한 일로 넘어간다.
     */
    void setDirective(@Nullable Directive directive) {
        cancelPrimitiveProgram("directive changed", true);
        this.directive = directive;
        ai.debug(directive == null ? "Directive cleared" : "Directive: " + directive.kind() + " " + directive.subject() + " from " + directive.requestedBy());
        if (directive != null && plan != null && !isCombatGoal(currentGoal)) {
            plan.cancel(ai);
            plan = null;
            journal.planEnded(ai.getTicks(), Experience.Outcome.INTERRUPTED, "asked to do something else");
        }
    }

    String getCurrentActionName() {
        Action action = plan == null ? null : plan.current();
        return action == null ? "None" : action.getName();
    }

    DecisionLog getLog() {
        return log;
    }

    @Nullable Situation getLastSituation() {
        return lastSituation;
    }

    // 현재 계획의 행동 목록. 실행 중인 행동은 대괄호로 표시한다.
    String describePlan() {
        return plan == null ? NO_PLAN : plan.describeProgress();
    }

    /**
     * 지금 하고 있는 일과 그 이유. 목표가 바뀔 때와, 같은 목표로 새 계획을 세울 때마다 그때의 상황으로 새로 적는다.
     * (같은 "장비 제작" 목표라도 돌 곡괭이를 만들 때와 돌 검을 만들 때의 이유는 다르다.)
     * 계획이 실행되는 동안에는 바꾸지 않으므로, 상황이 달라진 뒤에도 "이 행동을 시작한 이유"가 남는다.
     */
    @Nullable DecisionTrace currentTrace() {
        return activeTrace;
    }

    private void think(long now) {
        TickProfiler profiler = ai.getProfiler();
        long started = profiler.begin();
        Perception perception = ai.getPerceptionSystem().update();
        profiler.end(TickProfiler.Section.PERCEPTION, started);

        ai.getLighting().tick(ai);
        BlockPoint position = ai.getPosition();
        ai.getMemory().recordVisit(ai.getWorldId(), position);
        ai.getWorldModel().markExplored(ai.getWorldId(), position.x() >> 4, position.z() >> 4);
        if (now % MEMORY_PRUNE_INTERVAL == 0) ai.getMemory().prune(now);
        if (now % EQUIP_CHECK_INTERVAL == 0 && ai.getInventory().wearBestArmor()) ai.debug("Changed into better equipment");
        if (now % PERCEPTION_LOG_INTERVAL == 0) {
            ai.debug("Perception updated: health=" + (int) perception.getHealth() + " food=" + perception.getFood()
                    + " hostiles=" + perception.getHostiles().size() + " animals=" + perception.getAnimals().size()
                    + " drops=" + perception.getDrops().size() + " threats=" + perception.getThreats().size());
        }

        started = profiler.begin();
        releaseRestedGoals(position);
        Situation situation = SituationBuilder.build(ai, currentGoal);
        lastSituation = situation;
        Choice choice = choose(situation, now);
        profiler.end(TickProfiler.Section.DECISION, started);

        started = profiler.begin();
        journal.observe(situation, currentGoal, getCurrentActionName(), describePlan(), escaping, recovery.restCount(currentGoal));
        profiler.end(TickProfiler.Section.OBSERVATION, started);

        boolean planDone = plan == null || plan.isFinished();
        // 몬스터를 피해 달아나던 중에 용암이나 물에 빠지면 목표는 그대로 "위험 탈출"이라서 계획이 바뀌지 않는다.
        // 달아나는 행동으로는 헤엄쳐 나올 수 없으므로 계획을 버리고 다시 세우게 한다.
        if (!planDone && inHazard(situation) && plan.current() instanceof RunAwayAction
                && choice.goal() == GoalType.ESCAPE_DANGER && currentGoal == GoalType.ESCAPE_DANGER) {
            plan.cancel(ai);
            planDone = true;
            journal.planEnded(now, Experience.Outcome.INTERRUPTED, "fell into a hazard while running away");
        }
        // 긴급한 목표는 하던 행동을 끊고 바로 시작한다. 그 밖의 목표는 현재 계획이 끝난 뒤에 바꾼다.
        if (choice.goal() != currentGoal && (planDone || choice.score() >= GoalSystem.EMERGENCY_SCORE)) {
            switchGoal(choice, situation, now);
            planDone = true;
        }
        if (planDone) {
            // 목표는 그대로인데 새 계획을 세우는 경우, 이유를 지금 상황으로 다시 적는다.
            if (choice.goal() == currentGoal) activeTrace = trace(choice, situation, now, previousGoal);
            started = profiler.begin();
            makePlan(now);
            profiler.end(TickProfiler.Section.PLANNING, started);
        }
    }

    /**
     * 이번에 할 단기 목표를 정한다. 관리자가 고정한 목표가 가장 먼저이고, 그다음이 긴급 목표,
     * 그다음이 사람이 부탁한 일, 마지막이 스스로 고른 목표다.
     */
    private Choice choose(Situation situation, long now) {
        if (forcedGoal != null) return new Choice(forcedGoal, Double.MAX_VALUE, DecisionTrace.Origin.FORCED);

        GoalSystem.Selection selection = goals.select(situation, now);
        if (directive != null) {
            if (directive.isDone(situation) || directive.isExpired(now)) {
                ai.debug("Directive finished: " + directive.kind());
                directive = null;
            } else if (selection.score() < GoalSystem.EMERGENCY_SCORE) {
                GoalType requested = directive.goalFor(situation);
                // 부탁받은 일이 계속 실패해서 쉬는 중이면 그동안은 스스로 고른 일을 한다.
                if (!goals.isOnCooldown(requested, now)) return new Choice(requested, DIRECTIVE_SCORE, DecisionTrace.Origin.REQUESTED);
            }
        }
        return new Choice(selection.goal(), selection.score(), DecisionTrace.Origin.AUTONOMOUS);
    }

    /**
     * 쉬게 했던 목표 중, 그 뒤로 자리를 충분히 옮긴 것은 다시 시도할 수 있게 한다.
     * 실패는 대개 그 자리의 사정(놓을 자리가 없음, 길이 막힘) 때문이라, 다른 곳에 가면 될 수 있다.
     * 쉰 횟수는 지우지 않으므로 옮긴 자리에서도 또 실패하면 더 오래 쉰다.
     */
    private void releaseRestedGoals(BlockPoint position) {
        if (restedAt.isEmpty()) return;
        restedAt.entrySet().removeIf(entry -> {
            if (entry.getValue().distance(position) < REST_RELEASE_DISTANCE) return false;
            goals.clearCooldown(entry.getKey());
            ai.debug("Moved away from where " + entry.getKey() + " kept failing, allowing it again");
            return true;
        });
    }

    private static boolean inHazard(Situation situation) {
        return situation.inLava || situation.standingInDanger || situation.drowning || situation.suffocating;
    }

    private void switchGoal(Choice choice, Situation situation, long now) {
        ai.debug("Goal changed: " + currentGoal + " -> " + choice.goal());
        if (plan != null) plan.cancel(ai);
        plan = null;
        journal.planEnded(now, Experience.Outcome.INTERRUPTED, "switched to " + choice.goal());
        recovery.onGoalSwitched(currentGoal);
        ai.getMemory().setLastGoal(currentGoal.name());
        previousGoal = currentGoal;
        currentGoal = choice.goal();

        DecisionTrace trace = trace(choice, situation, now, previousGoal);
        activeTrace = trace;
        log.record(trace);
        ai.debug("Reason: " + trace.reason());
        ai.getTeam().announceGoal(ai, choice.goal());
    }

    private DecisionTrace trace(Choice choice, Situation situation, long now, @Nullable GoalType previous) {
        List<DecisionTrace.Candidate> candidates = new ArrayList<>();
        for (GoalSystem.Ranked ranked : goals.rank(situation, now, TRACE_CANDIDATES)) {
            candidates.add(new DecisionTrace.Candidate(ranked.goal(), ranked.score(), ranked.resting()));
        }
        String reason = switch (choice.origin()) {
            case FORCED -> "관리자가 이 목표로 고정해 두었어요";
            case REQUESTED -> directive != null ? directive.describe() : GoalReasons.explain(choice.goal(), situation);
            case AUTONOMOUS -> GoalReasons.explain(choice.goal(), situation);
        };
        return new DecisionTrace(now, choice.goal(), choice.score(), choice.origin(), situation.stage, situation.nextMilestone,
                candidates, reason, previous);
    }

    private void makePlan(long now) {
        // 구덩이나 나무 꼭대기처럼 걸어서는 어디로도 갈 수 없는 곳에 있으면, 어떤 목표든 먼저 길을 파서 빠져나온다.
        escaping = false;
        if (trappedStreak >= TRAPPED_THRESHOLD && !isCombatGoal(currentGoal)) {
            List<Action> way = planner.planEscape(currentGoal, ai);
            if (!way.isEmpty()) {
                plan = new Plan(currentGoal, way);
                escaping = true;
                journal.decided(now, activeTrace, LegacyGoalAdapter.toGoal(currentGoal, lastSituation), DIG_WAY_OUT, true, way);
                waitingForPlan = false;
                log.notePlan(plan.describe());
                ai.debug("Trapped, digging a way out: " + plan.describe());
                return;
            }
        }

        // 고른 목표를 "무엇을 얼마나"로 옮겨 적고, 그것을 맡는 스킬에게 계획을 세우게 한다.
        Goal spec = LegacyGoalAdapter.toGoal(currentGoal, lastSituation);
        SkillPlan chosen = planner.plan(spec, ai, lastSituation);
        List<Action> actions = chosen.actions();
        if (actions.isEmpty()) {
            actions = List.of(new WaitAction(NO_PLAN_WAIT_TICKS));
            journal.decided(now, activeTrace, spec, "", false, actions);
            journal.planEnded(now, Experience.Outcome.NO_PLAN, "no plan");
            ai.debug("No plan available for " + currentGoal);
            log.notePlan(NO_PLAN);
            log.noteFailure(now, currentGoal, NO_PLAN, "no plan");
            onPlanFailed(now, "no plan");
            plan = new Plan(currentGoal, actions);
            waitingForPlan = true;
            return;
        }
        plan = new Plan(currentGoal, actions);
        waitingForPlan = false;
        journal.decided(now, activeTrace, spec, chosen.skill(), false, actions);
        log.notePlan(plan.describe());
        ai.debug("Plan for " + currentGoal + ": " + plan.describe());
    }

    private void runAction(long now) {
        Action action = plan == null ? null : plan.current();
        if (action == null) return;

        if (controlledProgram != null) {
            action.update(ai);
            if (action.getStatus() == ActionStatus.SUCCESS || action.getStatus() == ActionStatus.FAILED) {
                lastPrimitiveResult = controlledProgram.result();
                controlledProgram = null;
                plan = null;
                if (primitiveLease && (lastPrimitiveResult.status().equals("SUCCEEDED")
                        || lastPrimitiveResult.status().equals("FAILED")
                        || lastPrimitiveResult.status().equals("OBJECTIVE_NOT_REACHED"))) {
                    primitiveIdleUntil = now + primitiveIdleTicks;
                } else {
                    primitiveLease = false;
                    primitiveLeaseWorld = null;
                    journal.beginEpisode(Experience.StartReason.START);
                }
            }
            return;
        }

        if (action.getStatus() == ActionStatus.READY) {
            ai.debug("Action started: " + action.getName());
            journal.actionStarted(now);
        }
        action.update(ai);

        if (action.getStatus() == ActionStatus.SUCCESS) {
            ai.debug("Action success: " + action.getName());
            journal.actionFinished(plan.position(), action, now);
            plan.advance();
            // 계획이 없어서 기다린 것은 성공으로 세지 않는다. 그래야 계획을 못 세우는 목표가 결국 쉬게 된다.
            if (plan.isFinished() && !waitingForPlan) onPlanSucceeded();
            if (plan.isFinished()) journal.planEnded(now, Experience.Outcome.SUCCEEDED, "");
        } else if (action.getStatus() == ActionStatus.FAILED) {
            journal.actionFinished(plan.position(), action, now);
            journal.planEnded(now, Experience.Outcome.FAILED, action.getName() + ": " + action.getFailReason());
            ai.debug("Action failed: " + action.getName() + " (" + action.getFailReason() + ")");
            ai.getMemory().recordFailure(action.getName(), action.getFailReason(), now);
            log.noteFailure(now, currentGoal, action.getName(), action.getFailReason());
            boolean trapped = NavigationSystem.FAIL_TRAPPED.equals(action.getFailReason());
            if (trapped) trappedStreak++;
            // 길을 한 단 파고 가 보니 아직 갇혀 있는 것은 목표의 실패로 세지 않는다. 바로 다음 단을 판다.
            if (recovery.countsAsFailure(trapped)) onPlanFailed(now, action.getName() + ": " + action.getFailReason());
            else trappedStreak = TRAPPED_THRESHOLD;
            // 계획의 나머지는 버리고, 다음 판단 때 현재 상황에 맞춰 다시 세운다.
            plan = null;
            ai.debug("Replanning...");
        }
    }

    private void onPlanSucceeded() {
        // 길을 파서 빠져나온 것은 목표를 이룬 것이 아니다. 그것까지 성공으로 세면, 길을 내도 닿지 못하는 목표가
        // "실패 → 탈출 → 실패"를 끝없이 되풀이하면서도 쉬지 않는다.
        if (escaping) recovery.onEscapeStep();
        else recovery.onPlanSucceeded(currentGoal);
        // 한 단을 파고 나온 직후에는 아직 갇혀 있을 수 있다. 다음 이동이 또 막히면 바로 이어서 판다.
        trappedStreak = escaping ? TRAPPED_THRESHOLD - 1 : 0;
    }

    private static boolean isCombatGoal(GoalType goal) {
        return goal == GoalType.ESCAPE_DANGER || goal == GoalType.SURVIVE || goal == GoalType.FIGHT_HOSTILE
                || goal == GoalType.ASSIST_ALLY;
    }

    /**
     * 같은 목표가 연달아 실패하면 그 목표를 쉬게 하고 다른 목표로 넘어간다. 돌아와서 또 실패하면 더 오래 쉰다.
     * 없어도 되는 중기 목표(집 등)가 거듭 막히면, 그 항목을 한동안 미뤄 두고 다음 단계를 진행하게 한다.
     */
    private void onPlanFailed(long now, String reason) {
        long rest = recovery.onPlanFailed(currentGoal);
        if (rest <= 0L) return;
        // 달리 할 일이 없을 때 하는 탐험까지 오래 쉬게 하면 그냥 서 있게 된다.
        if (currentGoal == GoalType.EXPLORE || currentGoal == GoalType.IDLE) rest = RecoveryTracker.BASE_REST_TICKS;
        goals.cooldown(currentGoal, now, rest);
        if (failsWhereItStands(currentGoal)) restedAt.put(currentGoal, ai.getPosition());
        log.noteGiveUp(now, currentGoal, rest, reason);
        ai.debug("Goal " + currentGoal + " keeps failing (" + reason + "), resting it for " + rest + " ticks");
        // 어디서 어떤 상태로 막혔는지 남긴다. 물에 떠 있거나 허공 위에 있으면 걷기와 파기가 모두 안 되므로 원인을 찾는 단서가 된다.
        ai.debug("Stuck at " + ai.getPosition() + (ai.getPlayer().isInWater() ? ", in water" : "")
                + (ai.getBody().isGrounded() ? "" : ", not on the ground"));

        // 실패한 목표가 바로 그 중기 목표를 이루려던 것일 때만 미룬다. (다 지은 집을 손보다 실패했다고 방패 만들기를 미루면 안 된다.)
        Milestone milestone = lastSituation == null ? null : lastSituation.nextMilestone;
        if (milestone == null || !milestone.isOptional() || recovery.restCount(currentGoal) < DEFER_AFTER_RESTS) return;
        boolean buildingIt = currentGoal == GoalType.BUILD_SHELTER && milestone == Milestone.SHELTER;
        boolean craftingIt = currentGoal == GoalType.CRAFT_TOOL && milestone.kind() == Milestone.Kind.CRAFT;
        if (!buildingIt && !craftingIt) return;

        ai.deferMilestone(milestone, DEFER_TICKS);
        recovery.onPlanSucceeded(currentGoal);
        // 같은 자리에서 계속 막혔으니, 다음에 다시 지을 때는 자리부터 새로 고른다.
        if (buildingIt) BuildPlans.abandon(ai);
        ai.debug("Deferring optional milestone " + milestone + " for " + DEFER_TICKS + " ticks and moving on");
    }

    /**
     * 실패가 "지금 서 있는 자리" 탓일 수 있는 목표인지. 이런 목표만 자리를 옮겼을 때 다시 시도한다.
     * 집의 상자나 침대처럼 정해진 곳으로 가야 하는 목표는, AI 가 어디에 서 있든 사정이 같으므로 해당하지 않는다.
     */
    private static boolean failsWhereItStands(GoalType goal) {
        return switch (goal) {
            case STORE_ITEMS, FETCH_ITEMS, SLEEP, RETURN_HOME, BUILD_SHELTER, LOOT_CHEST, SHARE_FOOD, ASSIST_ALLY, TEND_FURNACE -> false;
            default -> true;
        };
    }
}
