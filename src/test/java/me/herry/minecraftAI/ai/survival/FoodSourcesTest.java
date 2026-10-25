package me.herry.minecraftAI.ai.survival;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FoodSourcesTest {
    @Test void ordinaryCropsMustActuallyReachTheirMaximumAge() {
        assertFalse(FoodSources.ready(Material.WHEAT, 6, 7));
        assertTrue(FoodSources.ready(Material.WHEAT, 7, 7));
        assertFalse(FoodSources.ready(Material.CARROTS, 1, 7));
        assertFalse(FoodSources.ready(Material.POTATOES, 6, 7));
        assertFalse(FoodSources.ready(Material.BEETROOTS, 2, 3));
        assertTrue(FoodSources.ready(Material.BEETROOTS, 3, 3));
        assertFalse(FoodSources.ready(Material.WHEAT, -1, -1));
    }

    @Test void harvestableBerriesUseInteractionWithoutRequiringDestruction() {
        assertFalse(FoodSources.ready(Material.SWEET_BERRY_BUSH, 1, 3));
        assertTrue(FoodSources.ready(Material.SWEET_BERRY_BUSH, 2, 3));
        assertTrue(FoodSources.ready(Material.SWEET_BERRY_BUSH, 3, 3));
        assertTrue(FoodSources.usesInteraction(Material.SWEET_BERRY_BUSH));
        assertFalse(FoodSources.usesInteraction(Material.MELON));
    }

    @Test void rawResourcesAreNotSilentlyDeclaredReadyToEat() {
        assertEquals(Material.WHEAT, FoodSources.product(Material.WHEAT));
        assertEquals(Material.KELP, FoodSources.product(Material.KELP_PLANT));
        assertEquals(Material.MELON_SLICE, FoodSources.product(Material.MELON));
        assertNull(FoodSources.product(Material.PUMPKIN_STEM));
        assertFalse(FoodSources.ready(Material.PUMPKIN_STEM, 7, 7));
    }
}
