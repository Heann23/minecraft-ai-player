package me.herry.minecraftAI.ai.farming;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CropRulesTest {
    @Test void firstSeedCanEstablishProductionButExistingOrUnknownPlotsKeepTheReserve() {
        assertTrue(CropRules.canSpendOne(1, false, false));
        assertFalse(CropRules.canSpendOne(1, true, false));
        assertFalse(CropRules.canSpendOne(1, false, true));
        assertTrue(CropRules.canSpendOne(2, true, true));
        assertFalse(CropRules.canSpendOne(0, false, false));
    }

    @Test void propagationMapsToTheRealCropWithoutTurningWheatIntoImmediateFood() {
        assertEquals(Material.WHEAT, CropRules.crop(Material.WHEAT_SEEDS));
        assertEquals(Material.CARROTS, CropRules.crop(Material.CARROT));
        assertEquals(Material.POTATOES, CropRules.crop(Material.POTATO));
        assertEquals(Material.BEETROOT_SEEDS, CropRules.seed(Material.BEETROOTS));
        assertNull(CropRules.crop(Material.BREAD));
        assertNull(CropRules.seed(Material.MELON));
    }

    @Test void plantingRequiresObservedIrrigationLightAndAnEmptyCell() {
        assertTrue(CropRules.suitable(true, true, true, 9));
        assertFalse(CropRules.suitable(true, true, false, 15));
        assertFalse(CropRules.suitable(true, true, true, 8));
        assertFalse(CropRules.suitable(false, true, true, 15));
        assertFalse(CropRules.suitable(true, false, true, 15));
    }

    @Test void unsupportedSoilIsNotAdvertisedAsOneClickFarmland() {
        assertTrue(CropRules.tillable(Material.DIRT));
        assertTrue(CropRules.tillable(Material.GRASS_BLOCK));
        assertFalse(CropRules.tillable(Material.COARSE_DIRT));
        assertFalse(CropRules.tillable(Material.SAND));
    }
}
