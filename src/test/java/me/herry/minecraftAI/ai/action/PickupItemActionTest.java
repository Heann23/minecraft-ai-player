package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickupItemActionTest {
    @Test
    void exactPickupFailureMarksDropCellUnreachableWithBoundedCooldown() {
        var memory = new MemorySystem();
        UUID world = UUID.randomUUID();
        var cell = new BlockPoint(10, 64, -5);
        long tick = 500L;

        assertFalse(PickupItemAction.isUnreachable(memory, world, cell, tick));
        PickupItemAction.markUnreachable(memory, world, cell, tick, false);

        assertTrue(PickupItemAction.isUnreachable(memory, world, cell, tick));
        assertTrue(PickupItemAction.isUnreachable(memory, world, cell, tick + PickupItemAction.EXACT_UNREACHABLE_TTL - 1));
        assertFalse(PickupItemAction.isUnreachable(memory, world, cell, tick + PickupItemAction.EXACT_UNREACHABLE_TTL));

        // Scoped to the exact (world, cell) so a drop moving to an adjacent cell is eligible again.
        assertFalse(PickupItemAction.isUnreachable(memory, world, cell.offset(1, 0, 0), tick));
        assertFalse(PickupItemAction.isUnreachable(memory, UUID.randomUUID(), cell, tick));
    }

    @Test
    void autonomousPickupUsesFullUnreachableTtlWhenClearingAllowed() {
        var memory = new MemorySystem();
        UUID world = UUID.randomUUID();
        var cell = new BlockPoint(0, 70, 0);
        long tick = 100L;

        PickupItemAction.markUnreachable(memory, world, cell, tick, true);
        assertTrue(PickupItemAction.isUnreachable(memory, world, cell, tick + PickupItemAction.EXACT_UNREACHABLE_TTL));
        assertTrue(PickupItemAction.isUnreachable(memory, world, cell, tick + 1199L));
        assertFalse(PickupItemAction.isUnreachable(memory, world, cell, tick + 1200L));
    }
}
