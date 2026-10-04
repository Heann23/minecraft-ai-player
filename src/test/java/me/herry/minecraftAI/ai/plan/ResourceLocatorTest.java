package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLocatorTest {
    private static final BlockPoint FEET = new BlockPoint(-44, 64, 224);
    private static final Set<BlockPoint> STEPS = Set.of(FEET, new BlockPoint(-44, 65, 223));

    @Test
    void excludesCoalIronAndDiamondThatSupportTheRecordedShaft() {
        BlockPoint floor = new BlockPoint(-44, 63, 224);
        for (MemoryType ore : List.of(MemoryType.COAL_ORE, MemoryType.IRON_ORE, MemoryType.DIAMOND_ORE)) {
            assertFalse(ResourceLocator.canGatherAt(ore, floor, FEET, STEPS::contains), ore.name());
        }
    }

    @Test
    void stillMinesOreBelowTheFeetWhenItIsNotAShaftFloor() {
        BlockPoint ordinaryOre = new BlockPoint(-43, 63, 224);
        for (MemoryType ore : List.of(MemoryType.COAL_ORE, MemoryType.IRON_ORE, MemoryType.DIAMOND_ORE)) {
            assertTrue(ResourceLocator.canGatherAt(ore, ordinaryOre, FEET, STEPS::contains), ore.name());
        }
        assertFalse(ResourceLocator.canGatherAt(MemoryType.STONE, ordinaryOre, FEET, STEPS::contains));
    }
}
