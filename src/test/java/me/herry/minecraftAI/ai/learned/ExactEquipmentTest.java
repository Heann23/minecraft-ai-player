package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Numerical/menu-layout contracts, not actual armor/curse or survival acceptance. */
class ExactEquipmentTest {
    @Test void inventoryIndicesMapBijectivelyWithoutTouchingTheCraftingGrid() {
        Set<Integer> mapped = new HashSet<>();
        for (int index = 0; index <= 40; index++) assertTrue(mapped.add(ExactEquipment.menuSlot(index)));
        assertEquals(41, mapped.size());
        assertTrue(mapped.stream().noneMatch(slot -> slot < 5));
        assertEquals(8, ExactEquipment.menuSlot(36));
        assertEquals(5, ExactEquipment.menuSlot(39));
        assertEquals(45, ExactEquipment.menuSlot(40));
        assertEquals(-1, ExactEquipment.menuSlot(-1));
        assertEquals(-1, ExactEquipment.menuSlot(41));
    }

    @Test void partialArmorInsertionAndReplacementsNeverManufactureOrEraseAnItem() {
        assertEquals(new ExactEquipment.Amounts(63, 1), ExactEquipment.amounts(64, 0, 1));
        assertEquals(new ExactEquipment.Amounts(1, 0), ExactEquipment.amounts(0, 1, 64));
        assertEquals(new ExactEquipment.Amounts(1, 1), ExactEquipment.amounts(1, 1, 1));
        assertNull(ExactEquipment.amounts(64, 1, 1));
        for (int source = 0; source <= 64; source++) for (int target = 0; target <= 64; target++) {
            var result = ExactEquipment.amounts(source, target, 64);
            if (result != null) assertEquals(source + target, result.source() + result.destination());
        }
    }

    @Test void exactEquipmentChoiceSurvivesCommitmentAndFairCandidateSelectionWithoutACommandFallback() {
        var item = new ExactEquipment.Stack("DIRT", 2, "1".repeat(64), 0);
        var empty = new ExactEquipment.Stack("AIR", 0, "", 0);
        var choice = new ExactEquipment.Choice(UUID.randomUUID(), 1, 40, 0, item, empty);
        var candidate = new PolicyCandidates.Candidate("equip-exact:1:40", null, null, null,
                new PolicyFrame.Option("equip-exact:1:40", new double[PolicyFrame.ACTION_SIZE]), 0, "equip-exact:1:40", choice);
        var committed = candidate.committed(5);
        assertEquals(choice, committed.equipment());
        assertEquals(PrimitiveType.EQUIP_ITEM, committed.type());
        assertEquals(List.of(committed), PolicyCandidates.selectFair(List.of(committed), ""));
        assertThrows(IllegalArgumentException.class, () -> new ExactEquipment.Choice(UUID.randomUUID(), 1, 9, 0, item, empty));
    }
}
