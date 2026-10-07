package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveToActionTest {
    @Test
    void crossingIntoRefugeBlockDoesNotClearTheEntrance() {
        BlockPoint cell = new BlockPoint(1, -60, 0);
        BoundingBox partlyInside = new BoundingBox(0.72, -60, 0.2, 1.32, -58.2, 0.8);

        assertTrue(PathGoal.arrive(cell, 0.3).reached(1, -60, 0));
        assertFalse(MoveToAction.fitsInsideCell(partlyInside, cell));
        assertTrue(partlyInside.overlaps(new BoundingBox(0, -60, 0, 1, -59, 1)));
    }

    @Test
    void centeredBodyIsClearOfBothEntranceBlocks() {
        BlockPoint cell = new BlockPoint(1, -60, 0);
        BoundingBox inside = new BoundingBox(1.2, -60, 0.2, 1.8, -58.2, 0.8);

        assertTrue(MoveToAction.fitsInsideCell(inside, cell));
        assertFalse(inside.overlaps(new BoundingBox(0, -60, 0, 1, -59, 1)));
        assertFalse(inside.overlaps(new BoundingBox(0, -59, 0, 1, -58, 1)));
    }

    @Test
    void negativeCoordinatesAndOtherEntranceDirectionsUseTheActualBodyBounds() {
        BlockPoint cell = new BlockPoint(-3, 64, -2);
        assertTrue(MoveToAction.fitsInsideCell(new BoundingBox(-2.8, 64, -1.8, -2.2, 65.8, -1.2), cell));
        assertFalse(MoveToAction.fitsInsideCell(new BoundingBox(-2.8, 64, -2.2, -2.2, 65.8, -1.6), cell));
        assertFalse(MoveToAction.fitsInsideCell(new BoundingBox(-2.8, 63.7, -1.8, -2.2, 65.5, -1.2), cell));
    }
}
