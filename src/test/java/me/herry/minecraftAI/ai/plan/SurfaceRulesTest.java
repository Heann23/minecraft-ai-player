package me.herry.minecraftAI.ai.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceRulesTest {
    @Test
    void mountainRecessWithOpenSurroundingsIsNotDeepUnderground() {
        assertFalse(SurfaceRules.isDeepUnderground(false, 214, 207, 6, 4));
        assertFalse(SurfaceRules.isDeepUnderground(false, 214, 207, 6, 0));
    }

    @Test
    void buriedRoomAndTunnelKeepTheirUndergroundClassification() {
        assertTrue(SurfaceRules.isDeepUnderground(false, -49, -60, 6, 8));
        assertTrue(SurfaceRules.isDeepUnderground(false, 70, -53, 6, 5));
    }

    @Test
    void nearbyMountainDoesNotTurnOpenSkyIntoUnderground() {
        assertFalse(SurfaceRules.isDeepUnderground(false, 207, 207, 6, 8));
        assertFalse(SurfaceRules.isDeepUnderground(false, 212, 207, 6, 8));
    }

    @Test
    void netherCeilingIsStillNotASurfaceToClimbTowards() {
        assertFalse(SurfaceRules.isDeepUnderground(true, 128, 64, 6, 8));
        assertTrue(SurfaceRules.isUnderOpenSky(true, 128, 64));
    }
}
