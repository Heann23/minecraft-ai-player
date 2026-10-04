package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaftAccessTest {
    @Test
    void doesNotTreatANearbyIntactShaftAboveAnUnclimbableLedgeAsAccessible() {
        TerrainView ledge = (x, y, z) -> y < 64 + (x >= 3 ? 3 : 0) ? BlockClass.SOLID : BlockClass.OPEN;
        PathGoal shaft = PathGoal.arrive(new BlockPoint(4, 67, 0), 1.5);
        assertFalse(ShaftAccess.canReach(ledge, new BlockPoint(0, 64, 0), shaft));
    }

    @Test
    void usesAnExistingRouteWhenTheShaftCanBeReachedBySteps() {
        TerrainView stair = (x, y, z) -> y < 64 + Math.min(3, Math.max(0, x)) ? BlockClass.SOLID : BlockClass.OPEN;
        assertTrue(ShaftAccess.canReach(stair, new BlockPoint(0, 64, 0), PathGoal.arrive(new BlockPoint(4, 67, 0), 1.5)));
    }

    @Test
    void doesNotAcceptPartialProgressOrAnUnloadedAccessPoint() {
        GridTerrain terrain = new GridTerrain();
        for (int z = -21; z <= 21; z++) terrain.column(3, z, 64, 66, BlockClass.UNLOADED);
        assertFalse(ShaftAccess.canReach(terrain, new BlockPoint(0, 64, 0), PathGoal.arrive(new BlockPoint(6, 64, 0), 1.5)));
    }
}
