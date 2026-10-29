package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.observation.SurvivalObservation;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Actual game outcomes produce rewards; no Teacher decision, action score or assumed success is used as a label. */
public final class TrainingEpisode {
    public static final String REWARD_CONTRACT = "survival-progress/v2";
    private final String session = UUID.randomUUID().toString();
    private final Set<String> observedItems = new HashSet<>();
    private long sequence;
    private PolicyFrame before;
    private SurvivalObservation.Body beforeBody;
    private PpoBridge.Response chosen;
    private long appliedTick;

    public void begin(PolicyCandidates.Capture captured, PpoBridge.Response response, PolicyCandidates.Observed actual, long now) {
        if (before != null) throw new IllegalStateException("previous policy transition not closed");
        before = captured.frame(); beforeBody = actual.body(); chosen = response; appliedTick = now;
        if (sequence == 0) observedItems.addAll(actual.stock().keySet());
    }

    public PpoBridge.Transition finish(PolicyFrame next, SurvivalObservation.Body body, Map<String, Integer> stock,
                                       boolean terminated, boolean truncated, double actualObjectiveReward) {
        return finish(next, body, stock, terminated, truncated, actualObjectiveReward, java.util.List.of());
    }

    public PpoBridge.Transition finish(PolicyFrame next, SurvivalObservation.Body body, Map<String, Integer> stock,
                                       boolean terminated, boolean truncated, double actualObjectiveReward, java.util.List<String> objectiveEvents) {
        if (before == null) return null;
        long elapsed = next.serverTick() - appliedTick;
        if (elapsed < 0 || elapsed > 2400) {
            // An invalidated clock is not manufactured into a valid game step. Same-tick terminal events retain their actual zero duration.
            before = null; beforeBody = null; chosen = null; return null;
        }
        int discoveries = 0;
        for (var entry : stock.entrySet()) if (entry.getValue() > 0 && observedItems.add(entry.getKey())) discoveries++;
        // Explicit auxiliary reward, not a promised optimal survival strategy. Novel items are rewarded once per episode.
        var evidence = new PpoBridge.RewardEvidence("actual-reward-evidence/v1", beforeBody.health(), body.health(),
                beforeBody.foodLevel(), body.foodLevel(), beforeBody.saturation(), body.saturation(),
                beforeBody.remainingAir(), body.remainingAir(), discoveries, actualObjectiveReward);
        double reward = evidence.total((int) elapsed, terminated);
        var transition = new PpoBridge.Transition(session, sequence++, before, chosen.index(), chosen.logProbability(),
                chosen.value(), chosen.modelVersion(), reward, (int) elapsed, terminated, truncated, appliedTick, next.world().toString(), next.state(), REWARD_CONTRACT, objectiveEvents, evidence, next.taskMemory());
        before = null; beforeBody = null; chosen = null;
        return transition;
    }

    public boolean active() { return before != null; }
    public PolicyFrame before() { return before; }
}
