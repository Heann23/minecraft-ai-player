package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.primitive.control.ToolChoice;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Matches the policy's observed material and enchantments, including an empty hand. */
final class ToolChoiceItems {
    private ToolChoiceItems() {
    }

    static boolean matches(ItemStack item, ToolChoice choice) {
        if (item == null || item.isEmpty()) {
            return choice.material().equals("AIR") && choice.enchantments().isEmpty();
        }
        if (!item.getType().name().equals(choice.material())) return false;
        Map<String, Integer> enchantments = new HashMap<>();
        item.getEnchantments().forEach((enchantment, level) -> enchantments.put(enchantment.getKey().toString(), level));
        return enchantments.equals(choice.enchantments());
    }
}
