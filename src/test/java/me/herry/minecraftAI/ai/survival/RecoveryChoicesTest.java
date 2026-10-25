package me.herry.minecraftAI.ai.survival;

import me.herry.minecraftAI.ai.goal.GoalSystem;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.observation.SurvivalObservation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static me.herry.minecraftAI.ai.observation.SurvivalObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryChoicesTest {
    private static Effect effect(String key, int amplifier, int ticks) {
        return new Effect("minecraft:" + key, amplifier, ticks, false, key.equals("instant_health"), 1, "BENEFICIAL", true);
    }
    private static Item item(int slot, String label, int food, List<Effect> effects) {
        return new Item(slot, label, 2, "identity:" + label, -1, -1,
                new Consumption(Eligibility.AVAILABLE, food > 0 ? "EAT" : "DRINK", 32, food > 0, food, food,
                        food == 0, effects, List.of(), false, false, true, "GLASS_BOTTLE", "observed"));
    }
    private static SurvivalObservation snapshot(double health, int food, List<Item> items, List<Effect> active) {
        return new SurvivalObservation(1, 10, 10, "world", new Body(health, 20, 0, food, 0, 0, 300, 300),
                active, items, Map.of(), new ActiveUse(false, "NONE", "AIR", "", 0, 0), false);
    }

    @Test void fullHungerDoesNotHideAUsableHealingDrink() {
        var healing = item(4, "custom-healing-drink", 0, List.of(effect("instant_health", 0, 1)));
        var observed = snapshot(5, 20, List.of(healing), List.of());
        assertEquals(4, RecoveryChoices.rank(observed, true).getFirst().item().slot());
        assertTrue(RecoveryChoices.rank(observed, false).isEmpty());
        assertTrue(RecoveryChoices.rank(snapshot(20, 20, List.of(healing), List.of()), true).isEmpty());
    }

    @Test void namesDoNotSubstituteForActualEffectsAndUnknownOrHarmfulEffectsAreRejected() {
        var empty = item(0, "ENCHANTED_GOLDEN_APPLE", 0, List.of());
        var harmful = new Effect("minecraft:poison", 0, 100, false, false, 1, "HARMFUL", true);
        var poison = item(1, "custom-food", 4, List.of(harmful));
        var healing = item(2, "unnamed", 0, List.of(effect("regeneration", 0, 100)));
        var ranked = RecoveryChoices.rank(snapshot(6, 20, List.of(empty, poison, healing), List.of()), true);
        assertEquals(List.of(2), ranked.stream().map(c -> c.item().slot()).toList());
        var unknown = new Item(3, "unknown", 1, "id", -1, -1,
                new Consumption(Eligibility.UNKNOWN, "DRINK", 32, false, 0, 0, true,
                        List.of(), List.of(), false, false, false, "", "additional effect unknown"));
        assertFalse(RecoveryChoices.supported(unknown));
    }

    @Test void sustainedRegenerationAndDuplicateAbsorptionConserveSupplies() {
        var regen = effect("regeneration", 1, 400);
        var supply = item(0, "recovery", 0, List.of(effect("regeneration", 0, 100)));
        assertTrue(RecoveryChoices.rank(snapshot(12, 20, List.of(supply), List.of(regen)), true).isEmpty());
        assertFalse(RecoveryChoices.rank(snapshot(5, 20, List.of(supply), List.of()), true).isEmpty());
        var shield = item(1, "absorption", 0, List.of(effect("absorption", 0, 100)));
        assertTrue(RecoveryChoices.rank(snapshot(5, 20, List.of(shield), List.of(effect("absorption", 1, 400))), true).isEmpty());
    }

    @Test void nutritionTimeAndOpportunityCostCanFavorCommonFoodOverAMultiEffectItem() {
        var food = item(0, "bread", 5, List.of());
        var expensive = item(1, "many-effects", 4, List.of(effect("fire_resistance", 0, 1000), effect("night_vision", 0, 1000)));
        assertEquals(0, RecoveryChoices.rank(snapshot(18, 10, List.of(expensive, food), List.of()), true).getFirst().item().slot());
    }

    @Test void appliedNutritionAndEffectsAreDistinctFromAnimationAndConsumption() {
        var healing = item(0, "drink", 0, List.of(effect("instant_health", 0, 1)));
        var before = snapshot(4, 20, List.of(), List.of()).body();
        assertFalse(RecoveryChoices.benefitObserved(healing, before, List.of(), before, List.of()));
        assertTrue(RecoveryChoices.benefitObserved(healing, before, List.of(), snapshot(8, 20, List.of(), List.of()).body(), List.of()));
        var regen = item(1, "regen", 0, List.of(effect("regeneration", 0, 100)));
        assertTrue(RecoveryChoices.benefitObserved(regen, before, List.of(), before, List.of(effect("regeneration", 0, 100))));
        assertFalse(RecoveryChoices.benefitObserved(regen, before, List.of(effect("regeneration", 0, 100)), before,
                List.of(effect("regeneration", 0, 90))));
    }

    @Test void recoveryAvailabilityOpensSurvivalWhenHungerCannotRegenerateAndEscapeStillWins() {
        Situation s = new Situation();
        s.health = 4; s.food = 5; s.hasFood = false; s.canRegenerate = false;
        s.healthState = SurvivalSystem.HealthState.CRITICAL;
        GoalSystem goals = new GoalSystem();
        assertEquals(0, goals.score(GoalType.SURVIVE, s));
        s.recoveryAvailable = true;
        assertEquals(850, goals.score(GoalType.SURVIVE, s));
        s.inLava = true;
        assertTrue(goals.score(GoalType.ESCAPE_DANGER, s) > goals.score(GoalType.SURVIVE, s));
    }

    @Test void aBottleOrSingleDecrementIsPartialConsumptionEvidenceNotRecoverySuccess() {
        var stack = item(0, "drink", 0, List.of(effect("instant_health", 0, 1)));
        var oneLeft = new Item(0, stack.material(), 1, stack.fingerprint(), -1, -1, stack.consumption());
        assertTrue(RecoveryChoices.consumptionObserved(stack, oneLeft));
        assertFalse(RecoveryChoices.consumptionObserved(stack, stack));
        var bottle = new Item(0, "GLASS_BOTTLE", 1, "bottle", -1, -1, stack.consumption());
        assertFalse(RecoveryChoices.consumptionObserved(stack, bottle)); // losing two drinks is not one use
        assertTrue(RecoveryChoices.consumptionObserved(oneLeft, bottle));
        var different = new Item(0, "drink", 1, "changed-components", -1, -1, stack.consumption());
        assertFalse(RecoveryChoices.consumptionObserved(stack, different));
        var body = snapshot(4, 20, List.of(), List.of()).body();
        assertFalse(RecoveryChoices.benefitObserved(stack, body, List.of(), body, List.of()));
    }

    @Test void naturalRecoveryNeedsTheActualRuleWhileRegenerationCanOfferAnIndependentWait() {
        var body = snapshot(12, 20, List.of(), List.of()).body();
        assertNull(RecoveryChoices.rest(body, List.of(), false, true));
        assertNotNull(RecoveryChoices.rest(body, List.of(), true, true));
        assertNull(RecoveryChoices.rest(body, List.of(), true, false));
        var hungry = snapshot(12, 5, List.of(), List.of()).body();
        assertNull(RecoveryChoices.rest(hungry, List.of(), true, true));
        var regen = effect("regeneration", 0, 60);
        assertEquals(60, RecoveryChoices.rest(hungry, List.of(regen), false, true).ticks());
        assertNull(RecoveryChoices.rest(hungry, List.of(effect("regeneration", 0, 10)), false, true));
    }

    @Test void noncriticalNaturalRecoveryCanPreserveAHealingDrinkButCriticalRecoveryCanPreferIt() {
        var drink = item(0, "healing", 0, List.of(effect("instant_health", 0, 1)));
        var moderate = snapshot(12, 20, List.of(drink), List.of());
        assertTrue(RecoveryChoices.rest(moderate.body(), List.of(), true, true).value()
                > RecoveryChoices.rank(moderate, true).getFirst().value());
        var critical = snapshot(4, 20, List.of(drink), List.of());
        assertTrue(RecoveryChoices.rest(critical.body(), List.of(), true, true).value()
                < RecoveryChoices.rank(critical, true).getFirst().value());
        assertNull(RecoveryChoices.rest(snapshot(20, 20, List.of(), List.of()).body(), List.of(), true, true));
    }

    @Test void harmfulActiveEffectsDoNotOfferAnUnconditionalSafeRecoveryWait() {
        var poison = new Effect("minecraft:poison", 0, 100, false, false, 1, "HARMFUL", true);
        assertNull(RecoveryChoices.rest(snapshot(12, 20, List.of(), List.of()).body(), List.of(poison), true, true));
    }
}
