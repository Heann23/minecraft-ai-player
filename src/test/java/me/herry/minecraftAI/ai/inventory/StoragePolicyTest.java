package me.herry.minecraftAI.ai.inventory;

import me.herry.minecraftAI.ai.inventory.StoragePolicy.Context;
import me.herry.minecraftAI.ai.inventory.StoragePolicy.Kind;
import me.herry.minecraftAI.ai.inventory.StoragePolicy.Stack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoragePolicyTest {
    @Test
    void toolsAndArmorAreNeverStored() {
        List<Stack> stacks = List.of(new Stack(0, Kind.TOOL, 1), new Stack(1, Kind.ARMOR, 1), new Stack(2, Kind.KIT, 1), new Stack(3, Kind.OTHER, 64));

        assertTrue(StoragePolicy.deposits(stacks, Context.NOTHING_NEEDED).isEmpty());
    }

    // 귀한 것은 지니고 다니다 잃지 않도록 전부 넣는다.
    @Test
    void valuablesAreStoredEntirely() {
        List<Stack> stacks = List.of(new Stack(4, Kind.DIAMOND, 5), new Stack(5, Kind.VALUABLE, 20), new Stack(6, Kind.END, 3));

        Map<Integer, Integer> deposits = StoragePolicy.deposits(stacks, Context.NOTHING_NEEDED);
        assertEquals(5, deposits.get(4));
        assertEquals(20, deposits.get(5));
        assertEquals(3, deposits.get(6));
    }

    // 지금 만들려는 장비에 필요한 재료는 넣지 않는다. 넣었다가 바로 다시 꺼내러 가는 일을 막는다.
    @Test
    void neededMaterialsStayInTheBag() {
        List<Stack> stacks = List.of(new Stack(0, Kind.IRON, 12), new Stack(1, Kind.DIAMOND, 3), new Stack(2, Kind.STONE, 64), new Stack(3, Kind.LOG, 30));

        Map<Integer, Integer> needsIron = StoragePolicy.deposits(stacks, new Context(false, false, true, false));
        assertFalse(needsIron.containsKey(0));
        assertEquals(3, needsIron.get(1));

        Map<Integer, Integer> needsDiamond = StoragePolicy.deposits(stacks, new Context(false, false, false, true));
        assertEquals(12, needsDiamond.get(0));
        assertFalse(needsDiamond.containsKey(1));

        Map<Integer, Integer> needsStoneAndWood = StoragePolicy.deposits(stacks, new Context(true, true, false, false));
        assertFalse(needsStoneAndWood.containsKey(2));
        assertFalse(needsStoneAndWood.containsKey(3));
    }

    @Test
    void keepsACarryAmountAndStoresTheRest() {
        // 음식은 16개까지 지닌다. 앞쪽 칸(핫바)의 것을 먼저 남긴다.
        List<Stack> stacks = List.of(new Stack(0, Kind.FOOD, 10), new Stack(9, Kind.FOOD, 10), new Stack(20, Kind.FOOD, 10));

        Map<Integer, Integer> deposits = StoragePolicy.deposits(stacks, Context.NOTHING_NEEDED);
        assertFalse(deposits.containsKey(0));
        assertEquals(4, deposits.get(9));
        assertEquals(10, deposits.get(20));
    }

    @Test
    void carryLimitsAreSharedWithinAKind() {
        // 참나무 원목과 자작나무 원목은 같은 "원목"으로 합쳐서 센다.
        List<Stack> stacks = List.of(new Stack(0, Kind.LOG, 6), new Stack(1, Kind.LOG, 6), new Stack(2, Kind.TORCH, 20), new Stack(3, Kind.COAL, 40));

        Map<Integer, Integer> deposits = StoragePolicy.deposits(stacks, Context.NOTHING_NEEDED);
        assertEquals(4, deposits.get(1));
        assertFalse(deposits.containsKey(2));
        assertEquals(24, deposits.get(3));
    }

    @Test
    void keepsOneWorkbenchAndOneFurnace() {
        List<Stack> stacks = List.of(new Stack(0, Kind.TABLE, 1), new Stack(1, Kind.TABLE, 1), new Stack(2, Kind.FURNACE, 3));

        Map<Integer, Integer> deposits = StoragePolicy.deposits(stacks, Context.NOTHING_NEEDED);
        assertFalse(deposits.containsKey(0));
        assertEquals(1, deposits.get(1));
        assertEquals(2, deposits.get(2));
    }

    @Test
    void everyKindHasACategoryAndALimit() {
        for (Kind kind : Kind.values()) {
            assertTrue(StoragePolicy.carryLimit(kind, Context.NOTHING_NEEDED) >= 0, kind.name());
            assertTrue(kind.category() != null, kind.name());
        }
        assertEquals(StoragePolicy.Category.NETHER, Kind.NETHER.category());
        assertEquals(StoragePolicy.Category.FOOD, Kind.FOOD.category());
    }
}
