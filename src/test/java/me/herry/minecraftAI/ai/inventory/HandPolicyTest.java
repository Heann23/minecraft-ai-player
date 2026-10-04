package me.herry.minecraftAI.ai.inventory;

import me.herry.minecraftAI.ai.inventory.HandPolicy.Purpose;
import me.herry.minecraftAI.ai.inventory.HandPolicy.ToolOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandPolicyTest {
    private static final double HAND = ToolTier.NONE.combatPower();

    // 회귀: 나무 곡괭이(내구도 59)로 돼지를 잡다가 돌 곡괭이를 만들기 전에 부서졌다.
    @Test
    void huntsWithoutWearingOutMiningTools() {
        assertEquals(0.0, HandPolicy.weaponPower("WOODEN_PICKAXE", Purpose.HUNT));
        assertEquals(0.0, HandPolicy.weaponPower("IRON_PICKAXE", Purpose.HUNT));
        assertEquals(0.0, HandPolicy.weaponPower("STONE_SHOVEL", Purpose.HUNT));
        // 검과 도끼는 사냥에도 쓴다.
        assertTrue(HandPolicy.weaponPower("STONE_SWORD", Purpose.HUNT) > HAND);
        assertTrue(HandPolicy.weaponPower("WOODEN_AXE", Purpose.HUNT) > HAND);
    }

    @Test
    void fightsWithAPickaxeWhenThereIsNothingBetter() {
        double pickaxe = HandPolicy.weaponPower("WOODEN_PICKAXE", Purpose.FIGHT);
        double axe = HandPolicy.weaponPower("WOODEN_AXE", Purpose.FIGHT);
        double sword = HandPolicy.weaponPower("WOODEN_SWORD", Purpose.FIGHT);
        assertTrue(pickaxe > HAND);
        assertTrue(axe > pickaxe);
        assertTrue(sword > axe);
    }

    @Test
    void doesNotMistakeAPickaxeForAnAxe() {
        // "PICKAXE" 도 "AXE" 로 끝난다.
        assertTrue(HandPolicy.weaponPower("IRON_PICKAXE", Purpose.FIGHT) < HandPolicy.weaponPower("IRON_AXE", Purpose.FIGHT));
    }

    @Test
    void otherItemsAreNotWeapons() {
        assertEquals(0.0, HandPolicy.weaponPower("COBBLESTONE", Purpose.FIGHT));
        assertEquals(0.0, HandPolicy.weaponPower("IRON_INGOT", Purpose.FIGHT));
        assertEquals(0.0, HandPolicy.weaponPower("STONE_HOE", Purpose.FIGHT));
    }

    private static final float HAND_SPEED = 1.0F;
    // (칸 번호, 캐는 속도, 등급, 그 블록에서 아이템이 나오는지)
    private static final ToolOption WOODEN_PICKAXE = new ToolOption(0, 2.0F, ToolTier.WOOD.level(), true);
    private static final ToolOption STONE_PICKAXE = new ToolOption(1, 4.0F, ToolTier.STONE.level(), true);
    private static final ToolOption IRON_PICKAXE = new ToolOption(2, 6.0F, ToolTier.IRON.level(), true);
    private static final ToolOption DIAMOND_PICKAXE = new ToolOption(3, 8.0F, ToolTier.DIAMOND.level(), true);

    // 회귀: 굴을 파는 돌마다 철 곡괭이를 써서, 철 3개를 들여 만든 곡괭이가 6분 만에 부서졌다.
    @Test
    void digsStoneWithTheStonePickaxeAndSavesTheIronOne() {
        assertEquals(1, HandPolicy.toolSlot(List.of(WOODEN_PICKAXE, STONE_PICKAXE, IRON_PICKAXE), HAND_SPEED));
        // 싼 도구끼리는 빠른 것을 쓴다.
        assertEquals(1, HandPolicy.toolSlot(List.of(STONE_PICKAXE, WOODEN_PICKAXE), HAND_SPEED));
    }

    @Test
    void usesTheIronPickaxeWhereOnlyItWorks() {
        // 다이아몬드 광석은 돌 곡괭이로 캐면 아무것도 나오지 않는다.
        ToolOption stoneOnDiamond = new ToolOption(1, 4.0F, ToolTier.STONE.level(), false);
        assertEquals(2, HandPolicy.toolSlot(List.of(stoneOnDiamond, IRON_PICKAXE), HAND_SPEED));
        // 싼 곡괭이가 없으면 철 곡괭이로라도 캔다.
        assertEquals(2, HandPolicy.toolSlot(List.of(IRON_PICKAXE), HAND_SPEED));
    }

    @Test
    void amongValuableToolsUsesTheLowerTier() {
        assertEquals(2, HandPolicy.toolSlot(List.of(DIAMOND_PICKAXE, IRON_PICKAXE), HAND_SPEED));
    }

    @Test
    void usesNoToolWhenNoneIsFasterThanTheHand() {
        // 흙을 곡괭이로 캐도 맨손보다 빠르지 않다.
        ToolOption pickaxeOnDirt = new ToolOption(1, 1.0F, ToolTier.STONE.level(), true);
        assertEquals(-1, HandPolicy.toolSlot(List.of(pickaxeOnDirt), HAND_SPEED));
        assertEquals(-1, HandPolicy.toolSlot(List.of(), HAND_SPEED));
    }

    @Test
    void aToolThatGivesNoDropsIsStillBetterThanNothing() {
        ToolOption woodenOnIronOre = new ToolOption(0, 2.0F, ToolTier.WOOD.level(), false);
        assertEquals(0, HandPolicy.toolSlot(List.of(woodenOnIronOre), HAND_SPEED));
    }

    // 철 곡괭이가 있어도 돌 곡괭이 하나는 버리지 않는다. 그것으로 돌을 캐야 철 곡괭이가 남는다.
    @Test
    void keepsAStonePickaxeForDiggingNextToTheIronOne() {
        assertTrue(HandPolicy.isWorkPickaxe(ToolTier.STONE.level(), ToolTier.IRON.level()));
        assertTrue(HandPolicy.isWorkPickaxe(ToolTier.WOOD.level(), ToolTier.DIAMOND.level()));
        // 가장 좋은 곡괭이가 돌이면, 그보다 못한 나무 곡괭이는 그냥 낡은 도구다.
        assertFalse(HandPolicy.isWorkPickaxe(ToolTier.WOOD.level(), ToolTier.STONE.level()));
        assertFalse(HandPolicy.isWorkPickaxe(ToolTier.IRON.level(), ToolTier.DIAMOND.level()));
    }

    @Test
    void woodenBackupDoesNotReplaceAStonePickaxeForContinuedDigging() {
        assertFalse(HandPolicy.isAdequateWorkPickaxe(ToolTier.WOOD.level()));
        assertFalse(HandPolicy.isAdequateWorkPickaxe(ToolTier.GOLD.level()));
        assertTrue(HandPolicy.isAdequateWorkPickaxe(ToolTier.STONE.level()));
        assertTrue(HandPolicy.isAdequateWorkPickaxe(ToolTier.COPPER.level()));
        assertFalse(HandPolicy.isAdequateWorkPickaxe(ToolTier.IRON.level()));
        assertFalse(HandPolicy.isAdequateWorkPickaxe(ToolTier.NONE.level()));
    }

    @Test
    void keepsTheHandWhenItDoesNotWearOut() {
        boolean[] wearsOut = {false, true, true, false};
        assertEquals(0, HandPolicy.restSlot(wearsOut, 0));
    }

    @Test
    void putsAwayAToolForTheNearestSlotThatDoesNotWearOut() {
        // 0, 1 번은 도구, 2 번은 빈칸이거나 닳지 않는 아이템이다.
        boolean[] wearsOut = {true, true, false, false};
        assertEquals(2, HandPolicy.restSlot(wearsOut, 0));
    }

    @Test
    void swapsWithStorageWhenTheWholeHotbarIsTools() {
        // 핫바(앞쪽 칸)가 전부 도구면 그 뒤의 가방 칸을 고른다.
        boolean[] wearsOut = {true, true, true, true, false};
        assertEquals(4, HandPolicy.restSlot(wearsOut, 1));
    }

    @Test
    void keepsHoldingTheToolWhenEverythingWearsOut() {
        boolean[] wearsOut = {true, true, true};
        assertEquals(-1, HandPolicy.restSlot(wearsOut, 0));
    }
}
