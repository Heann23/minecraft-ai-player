package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.observation.SurvivalObservation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EffectEncodingTest {
    private static SurvivalObservation.Effect effect(String name, int strength, int duration, double probability) {
        return new SurvivalObservation.Effect("minecraft:" + name, strength, duration, false,
                name.equals("instant_health"), probability, "BENEFICIAL", true);
    }

    @Test void sameMaterialPotionsRemainDistinguishableByActualEffectRatherThanInventorySlot() {
        double[] healing = new double[PolicyFrame.ACTION_SIZE], fire = new double[PolicyFrame.ACTION_SIZE];
        EffectEncoding.encode(healing, List.of(effect("instant_health", 1, 1, 1)));
        EffectEncoding.encode(fire, List.of(effect("fire_resistance", 0, 3600, 1)));
        assertFalse(Arrays.equals(healing, fire));
        assertTrue(healing[64 + EffectEncoding.KEYS.indexOf("instant_health")] > 0);
        assertEquals(0, fire[64 + EffectEncoding.KEYS.indexOf("instant_health")]);
    }

    @Test void strengthDurationAndProbabilityAreSeparateObservedQuantities() {
        double[] encoded = new double[PolicyFrame.ACTION_SIZE];
        EffectEncoding.encode(encoded, List.of(effect("regeneration", 2, 1200, 0.25)));
        int index = EffectEncoding.KEYS.indexOf("regeneration");
        assertEquals(3 / 8.0, encoded[64 + index]);
        assertEquals(1, encoded[128 + index]);
        assertEquals(0.25, encoded[192 + index]);
        double[] unknown = new double[PolicyFrame.ACTION_SIZE];
        EffectEncoding.encode(unknown, List.of(effect("unmapped_effect", 0, 100, 1)));
        assertEquals(1, unknown[62]);
    }
}
