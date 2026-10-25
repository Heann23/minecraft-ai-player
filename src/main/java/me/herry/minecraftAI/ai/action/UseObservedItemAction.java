package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.observation.SurvivalCapture;
import me.herry.minecraftAI.ai.observation.SurvivalObservation;
import me.herry.minecraftAI.ai.plan.RecoveryPlans;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.survival.RecoveryChoices;
import org.bukkit.Bukkit;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Objects;

/** Exactly one selected food/drink via normal hand use. No item grants, refunds or direct effects. */
public final class UseObservedItemAction extends AbstractAction implements PrimitiveAction {
    public record Result(boolean consumed, boolean benefitObserved, String status, String reason,
                         SurvivalObservation.Body before, SurvivalObservation.Body after,
                         List<SurvivalObservation.Effect> effectsBefore, List<SurvivalObservation.Effect> effectsAfter) {
        public Result { effectsBefore = List.copyOf(effectsBefore); effectsAfter = List.copyOf(effectsAfter); }
    }

    private final String world;
    private final long observedAt;
    private final SurvivalObservation.Item selected;
    private int handSlot;
    private int consumedAt = -1;
    private boolean started, ownsInputs, consumed, benefit;
    private long damageGeneration, damageSequence;
    private SurvivalObservation.Body before;
    private List<SurvivalObservation.Effect> effectsBefore = List.of();
    private Result result;

    public UseObservedItemAction(String world, long observedAt, SurvivalObservation.Item selected) {
        super("UseObservedItem", timeout(selected));
        this.world = Objects.requireNonNull(world);
        this.observedAt = observedAt;
        this.selected = Objects.requireNonNull(selected);
    }

    private static int timeout(SurvivalObservation.Item selected) {
        Objects.requireNonNull(selected);
        return Math.min(240, Math.max(20, selected.consumption().useTicks() + 20));
    }

    @Override protected void onStart(AIPlayer ai) {
        long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        if (!world.equals(ai.getWorldId().toString()) || observedAt < 0 || now < observedAt || now - observedAt > 40) {
            fail("stale world/observation"); return;
        }
        if (!RecoveryChoices.supported(selected) || !RecoveryPlans.canPause(ai)) { fail("unsupported or unsafe use"); return; }
        var current = SurvivalCapture.item(ai, selected.slot());
        if (!current.fingerprint().equals(selected.fingerprint()) || current.amount() != selected.amount()
                || !RecoveryChoices.supported(current) || !current.consumption().equals(selected.consumption())) {
            fail("selected slot, effect description or availability changed"); return;
        }
        before = SurvivalCapture.body(ai.getPlayer());
        effectsBefore = SurvivalCapture.effects(ai.getPlayer());
        var risk = ai.riskObservation(); damageGeneration = risk.generation(); damageSequence = risk.lastSequence();
        ai.getNavigation().stop();
        ai.getBody().clearInputs(); ownsInputs = true;
        ai.getInventory().equipSlot(selected.slot());
        handSlot = ai.getPlayer().getInventory().getHeldItemSlot();
        var held = SurvivalCapture.item(ai, handSlot);
        if (!held.fingerprint().equals(selected.fingerprint()) || held.amount() != selected.amount()) fail("equipped item changed");
    }

    @Override protected void onTick(AIPlayer ai) {
        if (!world.equals(ai.getWorldId().toString()) || !ai.getBody().isUsable()) { fail("body or world changed"); return; }
        var risk = ai.riskObservation();
        if (risk.generation() != damageGeneration || risk.lastSequence() != damageSequence || !RecoveryPlans.canPause(ai)) {
            fail("recovery interrupted by damage or exposure"); return;
        }
        if (ai.getPlayer().getInventory().getHeldItemSlot() != handSlot) { fail("active slot changed"); return; }
        var hand = SurvivalCapture.item(ai, handSlot);
        boolean same = hand.fingerprint().equals(selected.fingerprint());
        if (started && !consumed && RecoveryChoices.consumptionObserved(selected, hand)) {
            consumed = true; consumedAt = getElapsed();
        }
        if (consumed) {
            benefit = RecoveryChoices.benefitObserved(selected, before, effectsBefore,
                    SurvivalCapture.body(ai.getPlayer()), SurvivalCapture.effects(ai.getPlayer()));
            if (benefit) succeed();
            else if (getElapsed() - consumedAt >= 5) fail("consumed; expected benefit not observed");
            return;
        }
        if (!same || hand.amount() != selected.amount()) { fail("hand item changed"); return; }
        if (!started) {
            ai.getPlayer().startUsingItem(EquipmentSlot.HAND); started = true;
        } else if (!ai.getPlayer().hasActiveItem() || ai.getPlayer().getActiveItemHand() != EquipmentSlot.HAND) {
            fail("normal hand use interrupted");
        }
    }

    @Override protected void onEnd(AIPlayer ai) {
        if (started) ai.getPlayer().clearActiveItem();
        if (ownsInputs) ai.getBody().clearInputs();
        // Record actual partial consumption even if the same tick's damage interrupted the expected benefit.
        if (before != null && world.equals(ai.getWorldId().toString()) && ai.getBody().isUsable()) {
            var hand = SurvivalCapture.item(ai, handSlot);
            consumed |= started && RecoveryChoices.consumptionObserved(selected, hand);
            var after = SurvivalCapture.body(ai.getPlayer());
            var effects = SurvivalCapture.effects(ai.getPlayer());
            benefit |= RecoveryChoices.benefitObserved(selected, before, effectsBefore, after, effects);
            result = new Result(consumed, benefit, getStatus().name(), getFailReason(), before, after, effectsBefore, effects);
        }
    }

    public Result result() { return result; }
    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.USE_ITEM; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Slot(selected.slot()); }
}
