package me.herry.minecraftAI.ai.plan;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceOptionsTest {
    @Test void deepslateAndBlackstoneAreNotForecastAsCobblestone() {
        assertEquals(Material.COBBLED_DEEPSLATE, ResourceOptions.stoneDrop(Material.DEEPSLATE, false));
        assertEquals(Material.BLACKSTONE, ResourceOptions.stoneDrop(Material.BLACKSTONE, false));
        assertEquals(Material.COBBLESTONE, ResourceOptions.stoneDrop(Material.STONE, false));
    }

    @Test void silkTouchChangesOnlyTheNaturalStoneForecast() {
        assertEquals(Material.STONE, ResourceOptions.stoneDrop(Material.STONE, true));
        assertEquals(Material.DEEPSLATE, ResourceOptions.stoneDrop(Material.DEEPSLATE, true));
        assertFalse(ResourceOptions.isStoneIngredient(ResourceOptions.stoneDrop(Material.STONE, true)));
        assertFalse(ResourceOptions.isStoneIngredient(ResourceOptions.stoneDrop(Material.DEEPSLATE, true)));
        assertEquals(Material.COBBLED_DEEPSLATE, ResourceOptions.stoneDrop(Material.COBBLED_DEEPSLATE, true));
        assertEquals(Material.BLACKSTONE, ResourceOptions.stoneDrop(Material.BLACKSTONE, true));
    }

    @Test void unrelatedOrUnsupportedSourcesDoNotBecomeGuaranteedIngredients() {
        assertNull(ResourceOptions.stoneDrop(Material.GRANITE, false));
        assertNull(ResourceOptions.stoneDrop(Material.IRON_ORE, false));
        assertFalse(ResourceOptions.isStoneIngredient(Material.GRANITE));
        assertTrue(ResourceOptions.isStoneIngredient(Material.COBBLED_DEEPSLATE));
    }
}
