package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.AbstractAction;
import me.herry.minecraftAI.ai.observation.SurvivalCapture;
import me.herry.minecraftAI.ai.observation.SurvivalObservation;
import org.bukkit.inventory.EquipmentSlot;

/** Learned policy chooses the item, including supported risky effects. The executor does not rank food or healing. */
public final class ConsumeExactAction extends AbstractAction {
    private final SurvivalObservation.Item selected;
    private final java.util.UUID world;
    private int held;
    private boolean using;

    public ConsumeExactAction(java.util.UUID world, SurvivalObservation.Item selected) {
        super("LearnedConsume[" + selected.slot() + "]", 220);
        this.world = world; this.selected = selected;
    }

    public static boolean supported(SurvivalObservation.Item item) {
        var use = item.consumption();
        return item.slot() >= 0 && item.amount() > 0 && !item.fingerprint().isEmpty()
                && use.eligibility() == SurvivalObservation.Eligibility.AVAILABLE && use.effectsKnown()
                && use.useTicks() >= 0 && use.useTicks() <= 200
                && (use.animation().equals("EAT") || use.animation().equals("DRINK"));
    }

    @Override protected void onStart(AIPlayer ai) {
        if (!world.equals(ai.getWorldId()) || !ai.getBody().isUsable() || !supported(selected)) { fail("unavailable item/body"); return; }
        var actual = SurvivalCapture.item(ai, selected.slot());
        if (!actual.fingerprint().equals(selected.fingerprint()) || actual.amount() != selected.amount() || !supported(actual)) {
            fail("stale selected item"); return;
        }
        ai.getNavigation().stop(); ai.getBody().clearInputs();
        if (!ai.getInventory().equipSlot(selected.slot())) { fail("cannot equip selected slot"); return; }
        held = ai.getPlayer().getInventory().getHeldItemSlot();
    }

    @Override protected void onTick(AIPlayer ai) {
        if (!world.equals(ai.getWorldId()) || !ai.getBody().isUsable()) { fail("body/world changed"); return; }
        if (ai.getPlayer().getInventory().getHeldItemSlot() != held) { fail("held slot changed"); return; }
        var actual = SurvivalCapture.item(ai, held);
        boolean same = actual.fingerprint().equals(selected.fingerprint());
        if (using && (same && actual.amount() == selected.amount() - 1 || selected.amount() == 1 && (actual.amount() == 0
                || actual.amount() == 1 && actual.material().equals(selected.consumption().remainderMaterial())))) {
            // This is normal item-use completion, not a claim of healing or survival; RL observes the actual next state.
            succeed(); return;
        }
        if (!same || actual.amount() != selected.amount()) { fail("hand contents changed"); return; }
        if (!using) { ai.getPlayer().startUsingItem(EquipmentSlot.HAND); using = true; }
        else if (!ai.getPlayer().hasActiveItem()) fail("item use interrupted");
    }

    @Override protected void onEnd(AIPlayer ai) {
        if (using) ai.getPlayer().clearActiveItem();
        ai.getBody().clearInputs();
    }
}
