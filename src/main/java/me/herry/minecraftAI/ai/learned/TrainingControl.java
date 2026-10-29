package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Chooses only; AIBrain retains sole ownership of Action/Plan/input execution. */
public final class TrainingControl {
    public record Decision(PolicyCandidates.Capture requested, PolicyCandidates.Candidate selected,
                           PpoBridge.Response response, PolicyCandidates.Observed appliedState, long appliedTick) {}
    private final PpoBridge bridge;
    private final Consumer<String> warn;
    private final PolicyCandidates catalog = new PolicyCandidates();
    private TrainingEpisode episode = new TrainingEpisode();
    private final List<PpoBridge.Transition> outbox = new ArrayList<>();
    private List<PpoBridge.Transition> sent = List.of();
    private PolicyCandidates.Capture requested;
    private CompletableFuture<PpoBridge.Response> pending;
    private double objectiveReward;
    private final AdvancementRewards advancementRewards = new AdvancementRewards();
    private final List<String> objectiveEvents = new ArrayList<>();
    private long warnAfter;
    private PpoBridge.Status latestStatus;
    private long latestReceivedTick;

    public TrainingControl(PpoBridge bridge, Consumer<String> warn) { this.bridge = bridge; this.warn = warn; }

    public Decision poll(AIPlayer ai, PolicyCandidates.Candidate active, int activeTicks, boolean requestAllowed) {
        long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        if (pending != null && pending.isDone()) {
            try {
                var response = pending.join();
                latestStatus = new PpoBridge.Status(response.modelVersion(), response.environmentSteps(), response.metadata()); latestReceivedTick = now;
                // Acknowledgement is independent of whether this delayed decision can still be executed.
                outbox.removeAll(sent);
                if (ai.getWorldId().equals(requested.frame().world()) && now >= requested.frame().serverTick()
                        && now - requested.frame().serverTick() <= 40 && ai.getBody().isUsable()) {
                    var actual = PolicyCandidates.observe(ai, active, activeTicks);
                    PolicyFrame after = new PolicyFrame(ai.getWorldId(), now, actual.features(), requested.frame().options(), false,
                            null, ai.getPolicyTaskMemory().observe(ai.getWorldId(), actual.stock()));
                    boolean learning = response.metadata() == null || response.metadata().learningEnabled();
                    var previous = episode.finish(after, actual.body(), actual.stock(), false, !learning, objectiveReward, objectiveEvents);
                    if (previous != null) outbox.add(previous);
                    // Invalidated clocks do not transfer old progress credit to a later action.
                    objectiveReward = 0; objectiveEvents.clear();
                    if (!learning) episode = new TrainingEpisode();
                    return new Decision(requested, requested.candidates().get(response.index()), response, actual, now);
                }
            } catch (RuntimeException failure) {
                if (now >= warnAfter) { warn.accept("PPO trainer unavailable; retaining only already admitted input, no Teacher fallback"); warnAfter = now + 200; }
            } finally { pending = null; sent = List.of(); }
        }
        if (pending == null && requestAllowed && !ai.getWorkBudget().isExhausted()) {
            try {
                requested = catalog.capture(ai, active, activeTicks);
                sent = List.copyOf(outbox);
                pending = bridge.request(requested.frame(), sent);
            } catch (RuntimeException failure) {
                if (now >= warnAfter) { warn.accept("PPO request deferred: " + failure.getClass().getSimpleName()); warnAfter = now + 200; }
                pending = null;
            }
        }
        return null;
    }

    public void applied(Decision decision) {
        if (decision.response().metadata() != null && !decision.response().metadata().learningEnabled()) return;
        episode.begin(decision.requested(), decision.response(), decision.appliedState(), decision.appliedTick());
    }

    public boolean isTransitioning() { return bridge.isTransitioning(); }
    public String brainId() { return latestStatus == null || latestStatus.metadata() == null ? null : latestStatus.metadata().brainId(); }
    public void acceptControl(PpoBridge.Status status) { acceptKnownStatus(status, Integer.toUnsignedLong(Bukkit.getCurrentTick())); }
    public void acceptKnownStatus(PpoBridge.Status status, long receivedTick) { latestStatus = status; latestReceivedTick = receivedTick; }
    public String modeLabel() {
        if (bridge.isTransitioning()) return "학습 설정 전환 중";
        if (latestStatus == null || latestStatus.metadata() == null) return "두뇌 연결 대기";
        return latestStatus.metadata().learningEnabled() ? "학습 중" : "고정 가중치 추론";
    }

    public List<String> details() {
        if (latestStatus == null) return List.of("두뇌 상태: 아직 학습 프로세스의 상태 응답을 받지 못함");
        var metadata = latestStatus.metadata();
        if (metadata == null) return List.of("두뇌 상태: 이전 프로토콜 응답 — 상세 정보 미기록");
        return List.of("두뇌 ID: " + metadata.brainId(), "학습 당시 시작 상태: " + metadata.trainingStartState(),
                "초기 가중치 상태: " + (metadata.initializationOnly() == null ? "미기록" : metadata.initializationOnly()),
                "학습 활성화: " + metadata.learningEnabled(), "최근 수신 정책 버전: " + latestStatus.modelVersion(),
                "누적 수집 전이: " + latestStatus.environmentSteps(), "가중치 갱신: " + metadata.gradientUpdates(),
                "가중치 학습에 사용한 전이: " + (metadata.trainedEnvironmentSteps() == null ? "미기록" : metadata.trainedEnvironmentSteps()),
                "아직 학습에 반영되지 않은 전이: " + (metadata.pendingRolloutTransitions() == null ? "미기록" : metadata.pendingRolloutTransitions()),
                "정책 저장 상태: " + metadata.saveState(),
                "체크포인트 해시: " + (metadata.checkpointSha256() == null ? "아직 없음" : metadata.checkpointSha256()),
                "상태 수신 후 경과 틱: " + Math.max(0, Integer.toUnsignedLong(Bukkit.getCurrentTick()) - latestReceivedTick),
                "게임 성능: 학습 횟수와 별개로 실제 평가 필요");
    }

    public void objective(double actualReward) {
        if (episode.active() && Double.isFinite(actualReward)) objectiveReward = Math.clamp(objectiveReward + actualReward, -100, 100);
    }

    public void advancement(String key, boolean completed, boolean displayed) {
        double reward = advancementRewards.observe(key, completed, displayed, episode.active() && objectiveEvents.size() < 32);
        if (reward > 0) { objective(reward); objectiveEvents.add("advancement:" + key); }
    }

    public void end(AIPlayer ai, PolicyCandidates.Candidate active, int activeTicks) {
        endAsync(ai, active, activeTicks).whenComplete((ignored, failure) -> {
            if (failure != null) warn.accept("Final PPO acknowledgement unavailable; do not assume the terminal transition was learned");
        });
    }

    public CompletableFuture<Void> endAsync(AIPlayer ai, PolicyCandidates.Candidate active, int activeTicks) {
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
        if (pending != null) pending.cancel(false);
        pending = null;
        try {
            if (episode.active()) {
                long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
                var actual = PolicyCandidates.observe(ai, active, activeTicks);
                PolicyFrame after = new PolicyFrame(ai.getWorldId(), now, actual.features(), episode.before().options(), false,
                        null, ai.getPolicyTaskMemory().observe(ai.getWorldId(), actual.stock()));
                var ended = episode.finish(after, actual.body(), actual.stock(), ai.getPlayer().isDead(), true, objectiveReward, objectiveEvents);
                if (ended != null) outbox.add(ended);
            }
            if (!outbox.isEmpty()) {
                var last = outbox.getLast();
                PolicyFrame terminal = new PolicyFrame(ai.getWorldId(), Integer.toUnsignedLong(Bukkit.getCurrentTick()),
                        last.nextState(), last.frame().options(), false);
                result = bridge.request(terminal, List.copyOf(outbox)).thenApply(ignored -> null);
            }
        } catch (RuntimeException failure) {
            warn.accept("Could not capture final RL state; missing transition is not converted into success");
            result = CompletableFuture.failedFuture(failure);
        } finally {
            outbox.clear(); sent = List.of(); requested = null; objectiveReward = 0; objectiveEvents.clear(); episode = new TrainingEpisode();
        }
        return result;
    }
}
