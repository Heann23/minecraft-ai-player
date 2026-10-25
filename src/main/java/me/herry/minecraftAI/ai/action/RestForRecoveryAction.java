package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.observation.SurvivalCapture;
import me.herry.minecraftAI.ai.plan.RecoveryPlans;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import org.bukkit.Bukkit;

import java.util.Objects;

/** Recovery wait has a live permission check and succeeds only when health actually rises. */
public final class RestForRecoveryAction extends AbstractAction implements PrimitiveAction {
    public record Result(int elapsedTicks, double healthBefore, double healthAfter, String status, String reason) {}
    private final String world;
    private final long observedAt;
    private final int ticks;
    private long generation, sequence;
    private double healthBefore;
    private double target;
    private boolean ownsInputs;
    private Result result;

    public RestForRecoveryAction(String world, long observedAt, int ticks) {
        super("RestForRecovery", Math.clamp(ticks, 1, 100) + 1);
        if (ticks < 1 || ticks > 100) throw new IllegalArgumentException("rest window 1..100 required");
        this.world = Objects.requireNonNull(world); this.observedAt = observedAt; this.ticks = ticks;
    }

    @Override protected void onStart(AIPlayer ai) {
        long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        if (!world.equals(ai.getWorldId().toString()) || observedAt < 0 || now < observedAt || now - observedAt > 40
                || !RecoveryPlans.canPause(ai) || RecoveryPlans.rest(ai) == null || ai.getPlayer().hasActiveItem()) {
            fail("stale or unavailable recovery wait"); return;
        }
        var body = SurvivalCapture.body(ai.getPlayer());
        healthBefore = body.health(); target = Math.min(body.maxHealth(), healthBefore + 2);
        var risk = ai.riskObservation(); generation = risk.generation(); sequence = risk.lastSequence();
        ai.getNavigation().stop(); ai.getBody().clearInputs(); ownsInputs = true;
    }

    @Override protected void onTick(AIPlayer ai) {
        if (!world.equals(ai.getWorldId().toString()) || !ai.getBody().isUsable()) { fail("body or world changed"); return; }
        var risk = ai.riskObservation();
        if (risk.generation() != generation || risk.lastSequence() != sequence || !RecoveryPlans.canPause(ai)
                || ai.getPlayer().hasActiveItem()) { fail("recovery wait interrupted"); return; }
        double health = ai.getPlayer().getHealth();
        if (health > healthBefore && (health >= target || getElapsed() >= ticks)) { succeed(); return; }
        if (RecoveryPlans.rest(ai) == null) { fail("recovery rule/effect no longer available"); return; }
        if (getElapsed() >= ticks) fail("wait elapsed without observed healing");
    }

    @Override protected void onEnd(AIPlayer ai) {
        if (ownsInputs) {
            ai.getBody().clearInputs();
            if (world.equals(ai.getWorldId().toString()) && ai.getBody().isUsable())
                result = new Result(getElapsed(), healthBefore, ai.getPlayer().getHealth(), getStatus().name(), getFailReason());
        }
    }

    public Result result() { return result; }
    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.WAIT; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Ticks(ticks); }
}
