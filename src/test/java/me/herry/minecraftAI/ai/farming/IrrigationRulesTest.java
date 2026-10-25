package me.herry.minecraftAI.ai.farming;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IrrigationRulesTest {
    private static final List<IrrigationRules.Cell> WALLS = List.of(IrrigationRules.Cell.WALL, IrrigationRules.Cell.WALL,
            IrrigationRules.Cell.WALL, IrrigationRules.Cell.WALL);

    @Test void oneCellBasinRequiresActualFloorAndFourClosedBanks() {
        assertTrue(IrrigationRules.available(new IrrigationRules.Basin(IrrigationRules.Cell.SOIL, IrrigationRules.Cell.WALL,
                WALLS, true, false, false, 4)));
        assertFalse(IrrigationRules.available(new IrrigationRules.Basin(IrrigationRules.Cell.SOIL, IrrigationRules.Cell.EMPTY,
                WALLS, true, false, false, 4)));
        assertFalse(IrrigationRules.available(new IrrigationRules.Basin(IrrigationRules.Cell.EMPTY, IrrigationRules.Cell.WALL,
                List.of(IrrigationRules.Cell.WALL, IrrigationRules.Cell.UNKNOWN, IrrigationRules.Cell.WALL, IrrigationRules.Cell.WALL),
                true, false, false, 4)));
    }

    @Test void evaporationProtectionAndInsufficientUsefulSoilCannotBecomeInstallationCandidates() {
        assertFalse(IrrigationRules.available(new IrrigationRules.Basin(IrrigationRules.Cell.SOIL, IrrigationRules.Cell.WALL,
                WALLS, true, true, false, 4)));
        assertFalse(IrrigationRules.available(new IrrigationRules.Basin(IrrigationRules.Cell.SOIL, IrrigationRules.Cell.WALL,
                WALLS, true, false, true, 4)));
        assertFalse(IrrigationRules.available(new IrrigationRules.Basin(IrrigationRules.Cell.SOIL, IrrigationRules.Cell.WALL,
                WALLS, true, false, false, 1)));
    }

    @Test void farmlandCropsAndNonFullBuildingShapesAreNeverExcavationOrContainmentMaterials() {
        assertTrue(IrrigationRules.excavatable(Material.GRASS_BLOCK));
        assertFalse(IrrigationRules.excavatable(Material.FARMLAND));
        assertFalse(IrrigationRules.excavatable(Material.CARROTS));
        assertFalse(IrrigationRules.containment(Material.OAK_STAIRS));
        assertFalse(IrrigationRules.containment(Material.CHEST));
        assertFalse(IrrigationRules.containment(Material.FARMLAND));
    }

    @Test void anAlreadyExcavatedContainedHoleCanResumeWithoutInventingSoilReplacement() {
        assertTrue(IrrigationRules.available(new IrrigationRules.Basin(IrrigationRules.Cell.EMPTY, IrrigationRules.Cell.WALL,
                WALLS, true, false, false, 2)));
        assertFalse(IrrigationRules.available(new IrrigationRules.Basin(IrrigationRules.Cell.SOURCE, IrrigationRules.Cell.WALL,
                WALLS, false, false, false, 2)));
    }
}
