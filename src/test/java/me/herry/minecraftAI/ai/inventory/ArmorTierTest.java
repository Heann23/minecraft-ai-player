package me.herry.minecraftAI.ai.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorTierTest {
    @Test
    void readsTierFromMaterialName() {
        assertEquals(ArmorTier.LEATHER, ArmorTier.ofName("LEATHER_CHESTPLATE"));
        assertEquals(ArmorTier.GOLD, ArmorTier.ofName("GOLDEN_HELMET"));
        assertEquals(ArmorTier.CHAINMAIL, ArmorTier.ofName("CHAINMAIL_LEGGINGS"));
        assertEquals(ArmorTier.IRON, ArmorTier.ofName("IRON_BOOTS"));
        assertEquals(ArmorTier.DIAMOND, ArmorTier.ofName("DIAMOND_CHESTPLATE"));
        assertEquals(ArmorTier.NETHERITE, ArmorTier.ofName("NETHERITE_HELMET"));
    }

    @Test
    void nonArmorHasNoTier() {
        // 철로 만들었어도 갑옷이 아니면 갑옷 등급이 없다.
        assertEquals(ArmorTier.NONE, ArmorTier.ofName("IRON_PICKAXE"));
        assertEquals(ArmorTier.NONE, ArmorTier.ofName("IRON_INGOT"));
        assertEquals(ArmorTier.NONE, ArmorTier.ofName("ELYTRA"));
        assertEquals(ArmorTier.NONE, ArmorTier.ofName("CARVED_PUMPKIN"));
    }

    @Test
    void tiersAreOrderedByProtection() {
        assertTrue(ArmorTier.IRON.isAtLeast(ArmorTier.IRON));
        assertTrue(ArmorTier.DIAMOND.isAtLeast(ArmorTier.IRON));
        assertFalse(ArmorTier.LEATHER.isAtLeast(ArmorTier.IRON));
        assertFalse(ArmorTier.CHAINMAIL.isAtLeast(ArmorTier.IRON));
        assertFalse(ArmorTier.GOLD.isAtLeast(ArmorTier.CHAINMAIL));
        assertFalse(ArmorTier.NONE.isAtLeast(ArmorTier.LEATHER));
    }

    @Test
    void readsSlotFromMaterialName() {
        assertEquals(ArmorSlot.HEAD, ArmorSlot.ofName("IRON_HELMET"));
        assertEquals(ArmorSlot.HEAD, ArmorSlot.ofName("TURTLE_HELMET"));
        assertEquals(ArmorSlot.CHEST, ArmorSlot.ofName("LEATHER_CHESTPLATE"));
        assertEquals(ArmorSlot.LEGS, ArmorSlot.ofName("DIAMOND_LEGGINGS"));
        assertEquals(ArmorSlot.FEET, ArmorSlot.ofName("GOLDEN_BOOTS"));
        assertNull(ArmorSlot.ofName("SHIELD"));
        assertNull(ArmorSlot.ofName("IRON_SWORD"));
    }
}
