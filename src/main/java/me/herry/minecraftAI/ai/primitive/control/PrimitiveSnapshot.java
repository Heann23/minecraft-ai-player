package me.herry.minecraftAI.ai.primitive.control;

import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable observation suitable for an independent policy; no live world objects. */
public record PrimitiveSnapshot(int schemaVersion, long tick, UUID worldId,
                                PrimitiveTarget.Point position, double health, int food, int air,
                                boolean alive, boolean inWater, boolean sneaking, List<ItemSlot> inventory) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public PrimitiveSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) throw new IllegalArgumentException("unsupported snapshot schema");
        if (tick < 0) throw new IllegalArgumentException("tick must be nonnegative");
        Objects.requireNonNull(worldId, "worldId");
        position = ContractValues.point(position);
        ContractValues.finiteRange(health, 0, 1024, "health");
        ContractValues.range(food, 0, 20, "food");
        ContractValues.range(air, -120_000, 120_000, "air");
        inventory = List.copyOf(Objects.requireNonNull(inventory, "inventory"));
        if (inventory.size() > 41) throw new IllegalArgumentException("inventory has at most 41 slots");
        var seen = new HashSet<Integer>();
        for (ItemSlot item : inventory) {
            if (!seen.add(item.slot())) throw new IllegalArgumentException("duplicate inventory slot " + item.slot());
        }
    }

    public int itemCount(String material) {
        Objects.requireNonNull(material, "material");
        int result = 0;
        for (ItemSlot item : inventory) {
            if (item.material().equals(material)) result = Math.addExact(result, item.amount());
        }
        return result;
    }

    public record ItemSlot(int slot, String material, int amount, int damage, int maxDamage,
                           Map<String, Integer> enchantments) {
        public ItemSlot {
            ContractValues.range(slot, 0, 40, "slot");
            material = ContractValues.material(material);
            ContractValues.range(amount, 0, 1024, "amount");
            if ((amount == 0) != material.equals("AIR")) {
                throw new IllegalArgumentException("empty slots are AIR with amount 0; occupied slots need a positive amount");
            }
            ContractValues.range(maxDamage, 0, 1_000_000, "maxDamage");
            ContractValues.range(damage, 0, maxDamage, "damage");
            enchantments = ContractValues.enchantments(enchantments);
        }
    }
}
