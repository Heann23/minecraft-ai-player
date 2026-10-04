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

    // 회귀: 해 지기 10초 전에 땅속에서 나무를 구하러 올라가기 시작해서, 지상에 닿았을 때는 밤이었다.
    @Test
    void surfaceTripsStopWellBeforeNightfall() {
        assertFalse(survival.tooLateForSurface(0));
        assertFalse(survival.tooLateForSurface(8999));
        assertTrue(survival.tooLateForSurface(9000));
        assertTrue(survival.tooLateForSurface(12800));
        assertTrue(survival.tooLateForSurface(18000));
        assertTrue(survival.tooLateForSurface(22999));
        // 새벽에 출발하면 지상에 닿을 때쯤 해가 떠 있다.
        assertFalse(survival.tooLateForSurface(23000));
    }
}
