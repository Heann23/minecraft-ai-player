package me.herry.minecraftAI.ai.primitive.control;

import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PrimitiveContractTest {
    private static final UUID WORLD = UUID.fromString("edb98376-eaba-46e4-a385-0b460a6e8a7b");
    private static final PrimitiveTarget.Point ORIGIN = new PrimitiveTarget.Point(0.5, 64, 0.5);

    @Test
    void blockActionSuccessDoesNotClaimCollectedResourceSuccess() {
        var before = snapshot(100, 20, true, List.of());
        var after = snapshot(120, 20, true, List.of());
        var objective = new TaskObjective.AcquireItem("DIAMOND", 1);
        var command = new PrimitiveCommand.BreakBlock(new BlockPoint(1, 64, 0),
                new ToolChoice(0, "DIAMOND_PICKAXE", Map.of()));
        var transition = new PrimitiveTransition(0, command, before, after, "SUCCESS", "",
                OutcomeSignals.between(before, after, objective.isAchieved(after)));
        var result = new PrimitiveResult(UUID.randomUUID(), "SUCCESS", "", objective.isAchieved(after),
                List.of(transition), after);

        assertEquals("SUCCESS", result.status());
        assertFalse(result.objectiveReached(), "a removed ore block is not an item in the inventory");
        assertFalse(result.transitions().getFirst().signals().objectiveReached());
        assertEquals(Map.of(), result.transitions().getFirst().signals().itemDelta());
    }

    @Test
    void collectedItemsDamageAndElapsedTimeRemainSeparateOutcomeSignals() {
        var before = snapshot(10, 20, true, List.of(item(0, "DIAMOND_PICKAXE", 1), item(1, "COBBLESTONE", 8)));
        var after = snapshot(45, 14, true, List.of(item(7, "DIAMOND_PICKAXE", 1),
                item(1, "COBBLESTONE", 5), item(2, "DIAMOND", 3)));
        var objective = new TaskObjective.AcquireItem("DIAMOND", 3);
        var signals = OutcomeSignals.between(before, after, objective.isAchieved(after));

        assertEquals(35, signals.elapsedTicks());
        assertEquals(6, signals.healthLost());
        assertFalse(signals.died());
        assertTrue(signals.objectiveReached());
        assertEquals(Map.of("COBBLESTONE", -3, "DIAMOND", 3), signals.itemDelta());
        assertFalse(signals.itemDelta().containsKey("DIAMOND_PICKAXE"), "slot changes are not item acquisition");
        assertThrows(UnsupportedOperationException.class, () -> signals.itemDelta().put("DIAMOND", 4));
    }

    @Test
    void toolsOfTheSameMaterialKeepExactEnchantmentAndSlotIdentity() {
        var mutable = new HashMap<>(Map.of("minecraft:silk_touch", 1));
        var silk = new ToolChoice(0, "DIAMOND_PICKAXE", mutable);
        var fortune = new ToolChoice(1, "DIAMOND_PICKAXE", Map.of("minecraft:fortune", 3));
        mutable.clear();

        assertEquals(Map.of("minecraft:silk_touch", 1), silk.enchantments());
        assertNotEquals(silk, fortune);
        assertEquals(silk, new PrimitiveCommand.BreakBlock(new BlockPoint(0, 63, 0), silk, true).tool());
        assertThrows(UnsupportedOperationException.class, () -> silk.enchantments().clear());
        assertThrows(IllegalArgumentException.class, () -> new ToolChoice(40, "DIAMOND_PICKAXE", Map.of()));
        assertThrows(NullPointerException.class, () -> new PrimitiveCommand.BreakBlock(new BlockPoint(0, 63, 0), null));
    }

    @Test
    void deathPreventsTaskAttainmentEvenWhenItemCountsAndPositionMatch() {
        var objective = new TaskObjective.All(List.of(new TaskObjective.AcquireItem("OBSIDIAN", 1),
                new TaskObjective.ReachPoint(ORIGIN, 0.5)));
        var alive = snapshot(0, 20, true, List.of(item(0, "OBSIDIAN", 1)));
        var dead = snapshot(20, 0, false, List.of(item(0, "OBSIDIAN", 1)));

        assertTrue(objective.isAchieved(alive));
        assertFalse(objective.isAchieved(dead));
        var signals = OutcomeSignals.between(alive, dead, objective.isAchieved(dead));
        assertTrue(signals.died());
        assertEquals(20, signals.healthLost());
        assertFalse(signals.objectiveReached());
    }

    @Test
    void programPreservesPolicyChosenOrderAndCannotBeMutatedAfterAdmission() {
        var steps = new ArrayList<PrimitiveCommand>();
        steps.add(new PrimitiveCommand.Wait(1));
        steps.add(new PrimitiveCommand.Jump());
        var program = program(steps);
        steps.clear();

        assertEquals(List.of(new PrimitiveCommand.Wait(1), new PrimitiveCommand.Jump()), program.steps());
        assertThrows(UnsupportedOperationException.class, () -> program.steps().clear());
        assertThrows(IllegalArgumentException.class, () -> program(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> program(java.util.Collections.nCopies(PrimitiveProgram.MAX_STEPS + 1, new PrimitiveCommand.Jump())));
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveProgram(1, UUID.randomUUID(), WORLD,
                0, PrimitiveProgram.MAX_TICKS + 1, "prepared-runtime-fixture", List.of(new PrimitiveCommand.Jump()),
                new TaskObjective.ReachPoint(ORIGIN, 1)));
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveProgram(2, UUID.randomUUID(), WORLD,
                0, 100, "prepared-runtime-fixture", List.of(new PrimitiveCommand.Jump()),
                new TaskObjective.ReachPoint(ORIGIN, 1)));
    }

    @Test
    void invalidPolicyValuesFailBeforeAnyWorldActionCanBeCreated() {
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveCommand.LookAt(
                new PrimitiveTarget.Point(Double.NaN, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveCommand.Pickup(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveCommand.SelectSlot(9));
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveCommand.Craft("STONE_PICKAXE", 0));
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveCommand.Wait(0));
        assertThrows(IllegalArgumentException.class, () -> new TaskObjective.All(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskObjective.AcquireItem("DIAMOND", 0));
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveCheck(false, ""));
        assertThrows(IllegalArgumentException.class, () -> OutcomeSignals.between(
                snapshot(2, 20, true, List.of()), snapshot(1, 20, true, List.of()), false));
    }

    @Test
    void snapshotRejectsDuplicatedSlotsAndDoesNotShareMutableObservationContainers() {
        var slots = new ArrayList<>(List.of(item(0, "DIAMOND", 2)));
        var observed = snapshot(0, 20, true, slots);
        slots.clear();

        assertEquals(2, observed.itemCount("DIAMOND"));
        assertThrows(UnsupportedOperationException.class, () -> observed.inventory().clear());
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 20, true,
                List.of(item(0, "DIAMOND", 1), item(0, "COAL", 1))));
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveSnapshot.ItemSlot(0,
                "DIAMOND_PICKAXE", 1, 100, 99, Map.of()));
        var empty = new PrimitiveSnapshot.ItemSlot(1, "AIR", 0, 0, 0, Map.of());
        assertEquals(0, snapshot(0, 20, true, List.of(empty)).itemCount("AIR"));
        assertThrows(IllegalArgumentException.class,
                () -> new PrimitiveSnapshot.ItemSlot(1, "DIAMOND", 0, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PrimitiveSnapshot.ItemSlot(1, "AIR", 1, 0, 0, Map.of()));
    }

    private static PrimitiveProgram program(List<PrimitiveCommand> steps) {
        return new PrimitiveProgram(1, UUID.randomUUID(), WORLD, 0, 100, "prepared-runtime-fixture", steps,
                new TaskObjective.ReachPoint(ORIGIN, 1));
    }

    private static PrimitiveSnapshot snapshot(long tick, double health, boolean alive,
                                               List<PrimitiveSnapshot.ItemSlot> inventory) {
        return new PrimitiveSnapshot(1, tick, WORLD, ORIGIN, health, 20, 300, alive, false, false, inventory);
    }

    private static PrimitiveSnapshot.ItemSlot item(int slot, String material, int amount) {
        return new PrimitiveSnapshot.ItemSlot(slot, material, amount, 0, 0, Map.of());
    }
}
