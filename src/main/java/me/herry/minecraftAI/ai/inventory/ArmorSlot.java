package me.herry.minecraftAI.ai.inventory;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * 갑옷을 입는 부위.
 */
public enum ArmorSlot {
    HEAD("_HELMET"),
    CHEST("_CHESTPLATE"),
    LEGS("_LEGGINGS"),
    FEET("_BOOTS");

    private final String suffix;

    ArmorSlot(String suffix) {
        this.suffix = suffix;
    }

    public static @Nullable ArmorSlot of(Material material) {
        return ofName(material.name());
    }

    // 그 부위에 입는 갑옷이면 부위를, 갑옷이 아니면 null 을 돌려준다. 겉날개나 호박처럼 방어력이 없는 것은 갑옷으로 치지 않는다.
    public static @Nullable ArmorSlot ofName(String name) {
        for (ArmorSlot slot : values()) {
            if (name.endsWith(slot.suffix)) return slot;
        }
        return null;
    }
}
