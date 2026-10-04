package me.herry.minecraftAI.ai.survival;

import me.herry.minecraftAI.ai.survival.SurvivalSystem.HealthState;
import me.herry.minecraftAI.ai.survival.SurvivalSystem.NightPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalSystemTest {
    private final SurvivalSystem survival = new SurvivalSystem(8, 4, 14);

    @Test
    void classifiesHealth() {
        assertEquals(HealthState.OK, survival.healthState(20));
        assertEquals(HealthState.OK, survival.healthState(9));
        assertEquals(HealthState.LOW, survival.healthState(8));
        assertEquals(HealthState.CRITICAL, survival.healthState(4));
    }

    @Test
    void eatsWhenHungry() {
        assertFalse(survival.shouldEat(20, 20, 20));
        assertFalse(survival.shouldEat(15, 20, 20));
        assertTrue(survival.shouldEat(13, 20, 20));
    }

    @Test
    void eatsEarlierWhenHurtSoHealthCanRegenerate() {
        assertTrue(survival.shouldEat(16, 10, 20));
        assertFalse(survival.shouldEat(18, 10, 20));
    }

    @Test
    void nightPolicyDependsOnWeapon() {
        assertEquals(NightPolicy.SHELTER, survival.nightPolicy(0.5));
        assertEquals(NightPolicy.SHELTER, survival.nightPolicy(1.3));
        assertEquals(NightPolicy.CONTINUE, survival.nightPolicy(1.7));
    }
}
