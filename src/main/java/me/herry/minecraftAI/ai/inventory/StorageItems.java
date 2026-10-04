package me.herry.minecraftAI.ai.inventory;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실제 아이템(Material)을 보관 정책이 쓰는 종류(StoragePolicy.Kind)로 나누고, 인벤토리와 상자의 내용을 읽는다.
 */
public final class StorageItems {
    private static final int STORAGE_SIZE = 36;
    private static final Map<Material, StoragePolicy.Kind> CACHE = new EnumMap<>(Material.class);
    private static final Set<Material> KIT = Set.of(Material.BUCKET, Material.WATER_BUCKET, Material.LAVA_BUCKET,
            Material.FLINT_AND_STEEL, Material.BOW, Material.ARROW, Material.SHIELD);
    private static final Set<Material> VALUABLE = Set.of(Material.EMERALD, Material.GOLD_INGOT, Material.RAW_GOLD, Material.RAW_COPPER,
            Material.COPPER_INGOT, Material.REDSTONE, Material.LAPIS_LAZULI, Material.OBSIDIAN, Material.FLINT, Material.STRING,
            Material.LEATHER, Material.GUNPOWDER, Material.BONE, Material.GOLD_NUGGET, Material.IRON_NUGGET, Material.QUARTZ);
    private static final Set<Material> NETHER = Set.of(Material.BLAZE_ROD, Material.BLAZE_POWDER);
    private static final Set<Material> END = Set.of(Material.ENDER_PEARL, Material.ENDER_EYE);

    private StorageItems() {
    }

    public static StoragePolicy.Kind kindOf(Material material) {
        return CACHE.computeIfAbsent(material, StorageItems::classify);
    }

    private static StoragePolicy.Kind classify(Material type) {
        if (KIT.contains(type)) return StoragePolicy.Kind.KIT;
        ItemCategory category = ItemCategory.of(type);
        if (category == ItemCategory.TOOL || category == ItemCategory.WEAPON) return StoragePolicy.Kind.TOOL;
        if (category == ItemCategory.ARMOR) return StoragePolicy.Kind.ARMOR;
        if (type == Material.CRAFTING_TABLE) return StoragePolicy.Kind.TABLE;
        if (type == Material.FURNACE) return StoragePolicy.Kind.FURNACE;
        if (type == Material.TORCH) return StoragePolicy.Kind.TORCH;
        if (type == Material.STICK) return StoragePolicy.Kind.STICK;
        if (type == Material.COAL || type == Material.CHARCOAL) return StoragePolicy.Kind.COAL;
        if (type == Material.RAW_IRON || type == Material.IRON_INGOT) return StoragePolicy.Kind.IRON;
        if (type == Material.DIAMOND) return StoragePolicy.Kind.DIAMOND;
        if (NETHER.contains(type)) return StoragePolicy.Kind.NETHER;
        if (END.contains(type)) return StoragePolicy.Kind.END;
        if (VALUABLE.contains(type)) return StoragePolicy.Kind.VALUABLE;
        if (Tag.LOGS.isTagged(type)) return StoragePolicy.Kind.LOG;
        if (Tag.PLANKS.isTagged(type)) return StoragePolicy.Kind.PLANK;
        if (Tag.WOOL.isTagged(type)) return StoragePolicy.Kind.WOOL;
        if (InventorySystem.isSafeFood(type)) return StoragePolicy.Kind.FOOD;
        if (Tag.ITEMS_STONE_TOOL_MATERIALS.isTagged(type)) return StoragePolicy.Kind.STONE;
        if (InventorySystem.isFiller(type)) return StoragePolicy.Kind.FILLER;
        return StoragePolicy.Kind.OTHER;
    }

    // 가방 36칸을 보관 정책이 읽을 수 있는 모양으로 바꾼다.
    public static List<StoragePolicy.Stack> stacksOf(PlayerInventory inventory) {
        List<StoragePolicy.Stack> stacks = new ArrayList<>();
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            stacks.add(new StoragePolicy.Stack(slot, kindOf(item.getType()), item.getAmount()));
        }
        return stacks;
    }

    // 상자에 든 것을 Material 이름별 개수로 센다. WorldModel 에 기억해 둘 내용이다.
    public static Map<String, Integer> contentsOf(Inventory inventory) {
        Map<String, Integer> contents = new HashMap<>();
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && !item.isEmpty()) contents.merge(item.getType().name(), item.getAmount(), Integer::sum);
        }
        return contents;
    }
}
