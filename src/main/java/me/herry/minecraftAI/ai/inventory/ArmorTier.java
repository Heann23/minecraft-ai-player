package me.herry.minecraftAI.ai.inventory;

import org.bukkit.Material;

/**
 * 갑옷의 재질 등급. level 이 높을수록 방어력이 높다 (가죽 7 < 구리 10 < 금 11 < 사슬 12 < 철 15 < 다이아몬드 20).
 */
public enum ArmorTier {
    NONE(0),
    LEATHER(1),
    COPPER(2),
    GOLD(3),
    CHAINMAIL(4),
    IRON(5),
    DIAMOND(6),
    NETHERITE(7);

    private final int level;

    ArmorTier(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public boolean isAtLeast(ArmorTier other) {
        return level >= other.level;
    }

    public static ArmorTier of(Material material) {
        return ofName(material.name());
    }

    // 바닐라 갑옷은 재질이 Material 이름의 앞부분으로만 구분된다. 갑옷이 아니면 NONE.
    public static ArmorTier ofName(String name) {
        if (ArmorSlot.ofName(name) == null) return NONE;
        if (name.startsWith("LEATHER_")) return LEATHER;
        if (name.startsWith("COPPER_")) return COPPER;
        if (name.startsWith("GOLDEN_")) return GOLD;
        // 거북 등딱지는 투구로만 있고 방어력이 사슬 투구와 같다.
        if (name.startsWith("CHAINMAIL_") || name.startsWith("TURTLE_")) return CHAINMAIL;
        if (name.startsWith("IRON_")) return IRON;
        if (name.startsWith("DIAMOND_")) return DIAMOND;
        if (name.startsWith("NETHERITE_")) return NETHERITE;
        return NONE;
    }
}
