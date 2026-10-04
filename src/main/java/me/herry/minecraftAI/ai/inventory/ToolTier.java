package me.herry.minecraftAI.ai.inventory;

import org.bukkit.Material;

/**
 * 도구와 무기의 재질 등급. level 이 높을수록 좋은 도구다.
 */
public enum ToolTier {
    NONE(0, 0.5),
    WOOD(1, 1.0),
    GOLD(1, 1.0),
    STONE(2, 1.4),
    COPPER(2, 1.4),
    IRON(3, 1.8),
    DIAMOND(4, 2.2),
    NETHERITE(5, 2.5);

    private final int level;
    // 전투 판단에 쓰는 상대적 공격력. 맨손이 0.5 다.
    private final double combatPower;

    ToolTier(int level, double combatPower) {
        this.level = level;
        this.combatPower = combatPower;
    }

    public int level() {
        return level;
    }

    public double combatPower() {
        return combatPower;
    }

    public boolean isAtLeast(ToolTier other) {
        return level >= other.level;
    }

    // 바닐라 도구는 재질이 Material 이름의 앞부분으로만 구분되어서 이름 접두사로 등급을 얻는다.
    public static ToolTier of(Material material) {
        return ofName(material.name());
    }

    public static ToolTier ofName(String name) {
        if (name.startsWith("WOODEN_")) return WOOD;
        if (name.startsWith("GOLDEN_")) return GOLD;
        if (name.startsWith("STONE_")) return STONE;
        if (name.startsWith("COPPER_")) return COPPER;
        if (name.startsWith("IRON_")) return IRON;
        if (name.startsWith("DIAMOND_")) return DIAMOND;
        if (name.startsWith("NETHERITE_")) return NETHERITE;
        return NONE;
    }
}
