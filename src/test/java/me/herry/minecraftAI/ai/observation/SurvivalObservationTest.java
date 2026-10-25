package me.herry.minecraftAI.ai.observation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static me.herry.minecraftAI.ai.observation.SurvivalObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalObservationTest {
    private static final Body BODY = new Body(6, 24, 4, 20, 5, 1, -1, 300);

    private static Consumption consumption(Eligibility eligibility, int ticks, boolean known, List<Effect> effects) {
        return new Consumption(eligibility, "DRINK", ticks, false, 0, 0, true, effects,
                List.of(), false, false, known, "GLASS_BOTTLE", "observed");
    }

    @Test void missingSlotsAreNotEmptyAndCannotAssertCompleteInventory() {
        var empty = new Item(0, "AIR", 0, "", -1, -1, consumption(Eligibility.NOT_CONSUMABLE, -1, true, List.of()));
        var snapshot = new SurvivalObservation(1, 4, 0xffff_ffffL, "world", BODY, List.of(), List.of(empty),
                Map.of(), new ActiveUse(false, "NONE", "AIR", "", 0, 0), false);
        assertEquals(-1, snapshot.body().remainingAir());
        assertFalse(snapshot.equipment().containsKey("OFF_HAND"));
        assertThrows(IllegalArgumentException.class, () -> new SurvivalObservation(1, 4, 1, "world", BODY,
                List.of(), List.of(empty), Map.of(), new ActiveUse(false, "NONE", "AIR", "", 0, 0), true));
    }

    @Test void unknownEffectsOrDurationCannotAdvertiseAvailableConsumption() {
        assertThrows(IllegalArgumentException.class, () -> consumption(Eligibility.AVAILABLE, -1, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> consumption(Eligibility.AVAILABLE, 32, false, List.of()));
        assertEquals(Eligibility.UNKNOWN, consumption(Eligibility.UNKNOWN, 32, false, List.of()).eligibility());
    }

    @Test void prospectiveEffectsAreImmutableAndDistinctFromActiveEffects() {
        var effects = new ArrayList<>(List.of(new Effect("minecraft:regeneration", 1, 100, false, false, 0.5, "BENEFICIAL", true)));
        var item = consumption(Eligibility.AVAILABLE, 32, true, effects);
        effects.clear();
        assertEquals(1, item.effects().size());
        assertEquals(0.5, item.effects().getFirst().probability());
        assertThrows(UnsupportedOperationException.class, () -> item.effects().clear());
    }

    @Test void consumeDurationUsesActualComponentAndPreservesUnknownValues() {
        assertEquals(32, SurvivalCapture.useTicks(1.6F));
        assertEquals(1, SurvivalCapture.useTicks(0.01F));
        assertEquals(0, SurvivalCapture.useTicks(0));
        assertEquals(-1, SurvivalCapture.useTicks(Float.NaN));
        assertEquals(-1, SurvivalCapture.useTicks(Float.POSITIVE_INFINITY));
        assertEquals(-1, SurvivalCapture.useTicks(-1));
    }
}
