package me.herry.minecraftAI.ai.learned;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.Map;

/** Current equipment facts for actor AND critic, never equipment rankings or assumed damage predictions. */
final class EquipmentPolicyContext {
    private EquipmentPolicyContext() {}

    static void capture(Player player, double[] state) {
        Attribute[] attributes = {Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS, Attribute.ATTACK_DAMAGE,
                Attribute.ATTACK_SPEED, Attribute.MAX_HEALTH};
        double[] scales = {30, 20, 20, 5, 20};
        for (int i = 0; i < attributes.length; i++) {
            var attribute = player.getAttribute(attributes[i]);
            state[128 + i] = attribute == null ? -1 : Math.clamp(attribute.getValue() / scales[i], 0, 16);
        }
        state[133] = player.isBlocking() ? 1 : 0;
        state[134] = player.isHandRaised() ? 1 : 0;
        state[135] = player.getAttackCooldown();
        int[] slots = {player.getInventory().getHeldItemSlot(), 40, 39, 38, 37, 36};
        for (int i = 0; i < slots.length; i++) {
            var stack = player.getInventory().getItem(slots[i]);
            if (stack == null || stack.isEmpty()) continue;
            Integer damage = stack.getData(DataComponentTypes.DAMAGE), maximum = stack.getData(DataComponentTypes.MAX_DAMAGE);
            var enchantments = new java.util.HashMap<String, Integer>();
            stack.getEnchantments().forEach((key, level) -> enchantments.put(key.getKey().toString(), level));
            encodeSlot(state, i, stack.getType().name(), stack.getAmount(),
                    maximum == null || maximum <= 0 ? 0 : Math.clamp((damage == null ? 0 : damage) / (double) maximum, 0, 1), enchantments);
        }
    }

    static void encodeSlot(double[] state, int slot, String material, int amount, double damage, Map<String, Integer> enchantments) {
        if (state.length != PolicyFrame.STATE_SIZE || slot < 0 || slot >= 6 || amount < 0
                || !Double.isFinite(damage) || damage < 0 || damage > 1) throw new IllegalArgumentException("invalid equipment context");
        if (amount == 0) return;
        int start = 136 + slot * 8;
        state[start] = 1;
        state[start + 1] = Math.min(16, amount / 64.0);
        state[start + 2] = damage;
        state[start + 3 + Math.floorMod(material.hashCode(), 4)] = 1;
        state[start + 7] = enchantments.containsKey("minecraft:binding_curse") ? 1 : 0;
        enchantments.forEach((key, level) -> {
            int index = 184 + Math.floorMod(key.hashCode(), 8);
            state[index] = Math.clamp(state[index] + level / 8.0, -16, 16);
        });
    }
}
