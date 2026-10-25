package me.herry.minecraftAI.ai.inventory;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForageReserveTest {
    @Test void foodIngredientsAndIndependentSeedTypesHaveBoundedReserves() {
        assertEquals(16, ForageReserve.minimum(Material.WHEAT));
        assertEquals(32, ForageReserve.minimum(Material.KELP));
        assertEquals(8, ForageReserve.minimum(Material.WHEAT_SEEDS));
        assertEquals(8, ForageReserve.minimum(Material.BEETROOT_SEEDS));
        assertEquals(0, ForageReserve.minimum(Material.COBBLESTONE));
    }
}
