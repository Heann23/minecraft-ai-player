package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.crafting.FuelMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FoodPreparationTest {
    @Test void aSingleItemCannotSimultaneouslyFillTheInputAndFuelSlot() {
        assertFalse(FoodPreparation.fuelAvailable(1, true, FuelMath.ITEMS_PER_WOOD));
        assertTrue(FoodPreparation.fuelAvailable(2, true, FuelMath.ITEMS_PER_WOOD));
    }

    @Test void separateFuelMustActuallyBeOwned() {
        assertFalse(FoodPreparation.fuelAvailable(0, false, FuelMath.ITEMS_PER_COAL));
        assertTrue(FoodPreparation.fuelAvailable(1, false, FuelMath.ITEMS_PER_COAL));
    }
}
