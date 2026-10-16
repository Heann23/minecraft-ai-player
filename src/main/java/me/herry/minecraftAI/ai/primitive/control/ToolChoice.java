package me.herry.minecraftAI.ai.primitive.control;

import java.util.Map;

/** Exact main-inventory slot fingerprint, including enchantments with namespaced keys. */
public record ToolChoice(int slot, String material, Map<String, Integer> enchantments) {
    public ToolChoice {
        ContractValues.range(slot, 0, 35, "slot");
        material = ContractValues.material(material);
        enchantments = ContractValues.enchantments(enchantments);
    }
}
