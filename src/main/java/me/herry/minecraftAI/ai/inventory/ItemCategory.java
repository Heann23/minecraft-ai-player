package me.herry.minecraftAI.ai.inventory;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.EnumMap;
import java.util.Map;

/**
 * 아이템의 용도별 분류. 아이템 이름 문자열이 아니라 Material 과 태그로 판단한다.
 */
public enum ItemCategory {
    FOOD,
    WOOD,
    STONE,
    TOOL,
    WEAPON,
    ARMOR,
    ORE,
    BLOCK,
    OTHER;

    private static final Map<Material, ItemCategory> CACHE = new EnumMap<>(Material.class);

    public static ItemCategory of(Material material) {
        return CACHE.computeIfAbsent(material, ItemCategory::classify);
    }

    private static ItemCategory classify(Material material) {
        if (Tag.ITEMS_SWORDS.isTagged(material)) return WEAPON;
        if (Tag.ITEMS_PICKAXES.isTagged(material) || Tag.ITEMS_AXES.isTagged(material)
                || Tag.ITEMS_SHOVELS.isTagged(material) || Tag.ITEMS_HOES.isTagged(material)) return TOOL;
        if (Tag.ITEMS_HEAD_ARMOR.isTagged(material) || Tag.ITEMS_CHEST_ARMOR.isTagged(material)
                || Tag.ITEMS_LEG_ARMOR.isTagged(material) || Tag.ITEMS_FOOT_ARMOR.isTagged(material)) return ARMOR;
        if (material.isEdible()) return FOOD;
        if (Tag.LOGS.isTagged(material) || Tag.PLANKS.isTagged(material) || material == Material.STICK) return WOOD;
        if (isOre(material)) return ORE;
        if (Tag.ITEMS_STONE_TOOL_MATERIALS.isTagged(material) || material == Material.STONE || material == Material.DEEPSLATE) return STONE;
        if (material.isBlock()) return BLOCK;
        return OTHER;
    }

    private static boolean isOre(Material material) {
        if (Tag.COAL_ORES.isTagged(material) || Tag.IRON_ORES.isTagged(material) || Tag.COPPER_ORES.isTagged(material)
                || Tag.GOLD_ORES.isTagged(material) || Tag.REDSTONE_ORES.isTagged(material) || Tag.LAPIS_ORES.isTagged(material)
                || Tag.DIAMOND_ORES.isTagged(material) || Tag.EMERALD_ORES.isTagged(material)) return true;
        return switch (material) {
            case COAL, RAW_IRON, RAW_COPPER, RAW_GOLD -> true;
            default -> false;
        };
    }
}
