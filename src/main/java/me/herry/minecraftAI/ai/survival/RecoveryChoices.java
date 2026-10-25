package me.herry.minecraftAI.ai.survival;

import me.herry.minecraftAI.ai.observation.SurvivalObservation;

import java.util.Comparator;
import java.util.List;

import static me.herry.minecraftAI.ai.observation.SurvivalObservation.*;

/** Relative recovery utility, not a promise of applied healing. Item names are not a recovery policy. */
public final class RecoveryChoices {
    public record Choice(Item item, double value, String reason) {}

    private RecoveryChoices() {}

    public static boolean supported(Item item) {
        Consumption c = item.consumption();
        return item.slot() >= 0 && item.amount() > 0 && !item.fingerprint().isEmpty()
                && c.eligibility() == Eligibility.AVAILABLE && c.effectsKnown() && c.useTicks() >= 0 && c.useTicks() <= 200
                && (c.animation().equals("EAT") || c.animation().equals("DRINK"))
                && !c.clearsEffects() && !c.randomTeleport() && c.removedEffects().isEmpty()
                && c.effects().stream().allMatch(e -> e.durationKnown() && e.category().equals("BENEFICIAL"))
                && (c.nutrition() == 0 || c.safeFood());
    }

    public static List<Choice> rank(SurvivalObservation observed, boolean safeToPause) {
        if (!safeToPause || observed.activeUse().active()) return List.of();
        return observed.storage().stream().filter(RecoveryChoices::supported)
                .map(item -> evaluate(item, observed.body(), observed.activeEffects()))
                .filter(choice -> choice.value() > 0)
                .sorted(Comparator.comparingDouble(Choice::value).reversed().thenComparingInt(c -> c.item().slot())).toList();
    }

    public static boolean consumptionObserved(Item selected, Item hand) {
        if (hand.fingerprint().equals(selected.fingerprint())) return hand.amount() == selected.amount() - 1;
        return selected.amount() == 1 && (hand.amount() == 0
                || !selected.consumption().remainderMaterial().isEmpty()
                && hand.material().equals(selected.consumption().remainderMaterial()) && hand.amount() == 1);
    }

    private static Choice evaluate(Item item, Body body, List<Effect> active) {
        Consumption c = item.consumption();
        double missing = Math.max(0, body.maxHealth() - body.health());
        double nutrition = Math.min(20 - body.foodLevel(), c.nutrition());
        double benefit = nutrition * 2 + (nutrition > 0 ? Math.min(4, c.saturation()) * 0.25 : 0);
        double effectCost = 0;
        for (Effect effect : c.effects()) {
            effectCost += 1; // Spending a multi-effect item has an opportunity cost even if only one effect helps.
            boolean covered = active.stream().anyMatch(existing -> existing.key().equals(effect.key())
                    && existing.amplifier() >= effect.amplifier()
                    && (existing.infinite() || existing.durationKnown() && existing.durationTicks() > c.useTicks() + 60));
            if (covered) continue;
            double relative = switch (effect.key()) {
                case "minecraft:instant_health" -> Math.min(missing, 4.0 * (effect.amplifier() + 1));
                case "minecraft:regeneration" -> missing > 0 ? Math.min(missing,
                        Math.min(8, (effect.durationTicks() < 0 ? 200 : effect.durationTicks()) / 25.0)
                                * (effect.amplifier() + 1)) : 0;
                case "minecraft:absorption" -> missing > 0 ? Math.max(0, Math.min(missing, 4.0 * (effect.amplifier() + 1)) - body.absorption()) * 0.5 : 0;
                case "minecraft:saturation" -> Math.min(20 - body.foodLevel(), 4.0 * (effect.amplifier() + 1));
                default -> 0;
            };
            benefit += relative * effect.probability();
        }
        // With adequate health and sustained regeneration already active, preserve effect-only recovery supplies.
        if (nutrition == 0 && body.health() >= body.maxHealth() * 0.5 && active.stream().anyMatch(e ->
                e.key().equals("minecraft:regeneration") && (e.infinite() || e.durationTicks() > c.useTicks() + 60))) benefit = 0;
        double cost = 1 + c.useTicks() / 20.0 + effectCost + 1.0 / item.amount();
        return new Choice(item, benefit / cost, "observed benefit=" + benefit + ", useTicks=" + c.useTicks()
                + ", resource/effect cost=" + cost + "; heuristic utility, actual result required");
    }

    /** Benefit and consumption must both be observed; animation or a missing item alone is not recovery. */
    public static boolean benefitObserved(Item selected, Body before, List<Effect> effectsBefore, Body after, List<Effect> effectsAfter) {
        if (selected.consumption().nutrition() > 0 && (after.foodLevel() > before.foodLevel() || after.saturation() > before.saturation())) return true;
        for (Effect expected : selected.consumption().effects()) {
            if (expected.key().equals("minecraft:instant_health") && after.health() > before.health()) return true;
            if (expected.key().equals("minecraft:absorption") && after.absorption() > before.absorption()) return true;
            if (expected.key().equals("minecraft:saturation") && after.foodLevel() > before.foodLevel()) return true;
            for (Effect actual : effectsAfter) {
                if (!actual.key().equals(expected.key()) || actual.amplifier() < expected.amplifier()) continue;
                boolean improved = effectsBefore.stream().noneMatch(old -> old.key().equals(actual.key())
                        && old.amplifier() >= actual.amplifier()
                        && (old.infinite() || !actual.infinite() && old.durationTicks() >= actual.durationTicks()));
                if (improved) return true;
            }
        }
        return false;
    }
}
