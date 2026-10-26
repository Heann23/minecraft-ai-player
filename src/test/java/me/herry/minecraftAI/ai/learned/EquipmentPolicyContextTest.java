package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentPolicyContextTest {
    @Test void equipmentInOneSlotCannotOverwriteOldObservationsOrOtherSlots() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        java.util.Arrays.fill(state, 0, 128, 0.25);
        EquipmentPolicyContext.encodeSlot(state, 2, "IRON_HELMET", 1, 0.5, Map.of("minecraft:binding_curse", 1));
        for (int i = 0; i < 128; i++) assertEquals(0.25, state[i]);
        for (int slot : new int[]{0, 1, 3, 4, 5})
            for (int i = 136 + slot * 8; i < 144 + slot * 8; i++) assertEquals(0, state[i]);
        assertEquals(1, state[152]);
        assertEquals(1 / 64.0, state[153]);
        assertEquals(0.5, state[154]);
        assertEquals(1, state[159]);
    }
    @Test void emptyEquipmentNeverCreatesArmorOrEnchantments() {
        double[] state = new double[PolicyFrame.STATE_SIZE];
        EquipmentPolicyContext.encodeSlot(state, 1, "AIR", 0, 0, Map.of());
        for (double value : state) assertEquals(0, value);
    }
}
