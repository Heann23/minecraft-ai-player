package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomePlansTest {
    @Test
    void bedAboveDeepMineIsNotNearbyEvenWithSmallHorizontalDistance() {
        assertFalse(HomePlans.sleepTripInRange(new BlockPoint(451, -8, 422), new BlockPoint(456, 64, 385)));
        assertFalse(HomePlans.sleepTripInRange(new BlockPoint(0, -60, 0), new BlockPoint(0, 1, 0)));
    }

    @Test
    void nearbySurfaceBedAndSmallHeightDifferenceRemainReachable() {
        assertTrue(HomePlans.sleepTripInRange(new BlockPoint(0, 64, 0), new BlockPoint(48, 64, 0)));
        assertTrue(HomePlans.sleepTripInRange(new BlockPoint(0, 64, 0), new BlockPoint(8, 68, 8)));
        assertFalse(HomePlans.sleepTripInRange(new BlockPoint(0, 64, 0), new BlockPoint(48, 65, 0)));
    }
}
