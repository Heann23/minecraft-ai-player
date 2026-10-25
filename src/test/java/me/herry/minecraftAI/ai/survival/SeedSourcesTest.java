package me.herry.minecraftAI.ai.survival;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SeedSourcesTest {
    @Test void seedSourcesExcludePlantsWithDifferentDrops() {
        assertTrue(SeedSources.isSource(Material.SHORT_GRASS));
        assertTrue(SeedSources.isSource(Material.TALL_GRASS));
        assertTrue(SeedSources.isSource(Material.FERN));
        assertTrue(SeedSources.isSource(Material.LARGE_FERN));
        assertFalse(SeedSources.isSource(Material.SEAGRASS));
        assertFalse(SeedSources.isSource(Material.DEAD_BUSH));
        assertFalse(SeedSources.isSource(Material.OAK_SAPLING));
        assertFalse(SeedSources.isSource(Material.WHEAT));
    }

    @Test void preservingThePlantIsNotSeedGathering() {
        assertFalse(SeedSources.allowsHand(Material.SHEARS, Map.of()));
        assertFalse(SeedSources.allowsHand(Material.DIAMOND_HOE, Map.of("minecraft:silk_touch", 1)));
        assertFalse(SeedSources.allowsHand(Material.IRON_PICKAXE, Map.of("silk_touch", 1)));
    }

    @Test void bareHandsAndNonPreservingStacksCanAttemptProbabilisticDrops() {
        assertTrue(SeedSources.allowsHand(Material.AIR, Map.of()));
        assertTrue(SeedSources.allowsHand(Material.STICK, Map.of()));
        assertTrue(SeedSources.allowsHand(Material.IRON_HOE, Map.of("minecraft:fortune", 3)));
    }
}
