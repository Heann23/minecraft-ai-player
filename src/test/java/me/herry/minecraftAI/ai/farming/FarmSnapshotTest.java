package me.herry.minecraftAI.ai.farming;

import me.herry.minecraftAI.ai.plan.FarmingPlans;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FarmSnapshotTest {
    @Test void unknownPlotIsDistinctFromObservedEmptyAndMaturePlots() {
        var world = UUID.randomUUID();
        var soil = new BlockPoint(1, 63, 2);
        var unknown = new FarmingPlans.Crop(world, soil, Material.AIR, Material.AIR, FarmingPlans.Stage.UNKNOWN,
                -1, -1, false, false, -1);
        var empty = new FarmingPlans.Crop(world, soil, Material.AIR, Material.AIR, FarmingPlans.Stage.EMPTY,
                -1, -1, true, true, 15);
        assertNotEquals(unknown.stage(), empty.stage());
        assertFalse(unknown.waterObserved());
    }

    @Test void snapshotsCannotBeMutatedAndIncompleteSurveyDoesNotMeanNoFarms() {
        var crops = new ArrayList<FarmingPlans.Crop>();
        crops.add(new FarmingPlans.Crop(UUID.randomUUID(), new BlockPoint(0, 63, 0), Material.WHEAT_SEEDS,
                Material.WHEAT, FarmingPlans.Stage.PLANTED, 2, 7, true, true, 15));
        var snapshot = new FarmingPlans.Snapshot(List.of(), crops, false);
        crops.clear();
        assertFalse(snapshot.complete());
        assertEquals(1, snapshot.crops().size());
        assertEquals(FarmingPlans.Stage.PLANTED, snapshot.crops().getFirst().stage());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.crops().clear());
    }
}
