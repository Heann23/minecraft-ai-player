package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.observation.SurvivalObservation;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Actual game outcomes produce rewards; no Teacher decision, action score or assumed success is used as a label. */
public final class TrainingEpisode {
    public static final String REWARD_CONTRACT = "survival-progress/v1";
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
        if (before == null) return null;
        long elapsed = next.serverTick() - appliedTick;
        if (elapsed < 0 || elapsed > 2400) {
            // An invalidated clock is not manufactured into a valid game step. Same-tick terminal events retain their actual zero duration.
            before = null; beforeBody = null; chosen = null; return null;
        }
        int discoveries = 0;
        for (var entry : stock.entrySet()) if (entry.getValue() > 0 && observedItems.add(entry.getKey())) discoveries++;
        // Explicit auxiliary reward, not a promised optimal survival strategy. Novel items are rewarded once per episode.
        double reward = elapsed * 0.0001 + (body.health() - beforeBody.health()) * 0.05
                + discoveries * 0.2 + actualObjectiveReward - (terminated ? 10 : 0);
        reward = Math.clamp(reward, -100, 100);
        var transition = new PpoBridge.Transition(session, sequence++, before, chosen.index(), chosen.logProbability(),
                chosen.value(), chosen.modelVersion(), reward, (int) elapsed, terminated, truncated, appliedTick, next.world().toString(), next.state());
        before = null; beforeBody = null; chosen = null;
        return transition;
    }

    public boolean active() { return before != null; }
    public PolicyFrame before() { return before; }
}
