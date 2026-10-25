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
    private long warnAfter;

    public TrainingControl(PpoBridge bridge, Consumer<String> warn) { this.bridge = bridge; this.warn = warn; }

    public Decision poll(AIPlayer ai, PolicyCandidates.Candidate active, int activeTicks, boolean requestAllowed) {
        long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        if (pending != null && pending.isDone()) {
            try {
                var response = pending.join();
                // Acknowledgement is independent of whether this delayed decision can still be executed.
                outbox.removeAll(sent);
                if (ai.getWorldId().equals(requested.frame().world()) && now >= requested.frame().serverTick()
                        && now - requested.frame().serverTick() <= 40 && ai.getBody().isUsable()) {
                    var actual = PolicyCandidates.observe(ai, active, activeTicks);
                    PolicyFrame after = new PolicyFrame(ai.getWorldId(), now, actual.features(), requested.frame().options(), false);
                    var previous = episode.finish(after, actual.body(), actual.stock(), false, false, objectiveReward);
                    if (previous != null) { outbox.add(previous); objectiveReward = 0; }
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
        episode.begin(decision.requested(), decision.response(), decision.appliedState(), decision.appliedTick());
    }

    public void objective(double actualReward) {
        if (episode.active() && Double.isFinite(actualReward)) objectiveReward = Math.clamp(objectiveReward + actualReward, -100, 100);
    }

    public void end(AIPlayer ai, PolicyCandidates.Candidate active, int activeTicks) {
        if (pending != null) pending.cancel(false);
        pending = null;
        try {
            if (episode.active()) {
                long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
                var actual = PolicyCandidates.observe(ai, active, activeTicks);
                PolicyFrame after = new PolicyFrame(ai.getWorldId(), now, actual.features(), episode.before().options(), false);
                var ended = episode.finish(after, actual.body(), actual.stock(), ai.getPlayer().isDead(), true, objectiveReward);
                if (ended != null) outbox.add(ended);
            }
            if (!outbox.isEmpty()) {
                var last = outbox.getLast();
                PolicyFrame terminal = new PolicyFrame(ai.getWorldId(), Integer.toUnsignedLong(Bukkit.getCurrentTick()),
                        last.nextState(), last.frame().options(), false);
                bridge.request(terminal, List.copyOf(outbox)).whenComplete((ignored, failure) -> {
                    if (failure != null) warn.accept("Final PPO acknowledgement unavailable; do not assume the terminal transition was learned");
                });
            }
        } catch (RuntimeException failure) {
            warn.accept("Could not capture final RL state; missing transition is not converted into success");
        } finally {
            outbox.clear(); sent = List.of(); requested = null; objectiveReward = 0; episode = new TrainingEpisode();
        }
    }
}
