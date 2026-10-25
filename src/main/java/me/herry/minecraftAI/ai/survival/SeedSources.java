package me.herry.minecraftAI.ai.survival;

import org.bukkit.Material;

import java.util.Map;

/** Grass can yield wheat seeds; recognizing a source does not promise a drop. */
public final class SeedSources {
    private SeedSources() {}

    public static boolean isSource(Material material) {
        return switch (material) {
            case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN -> true;
            default -> false;
        };
    }

    /** Shearing or preserving the plant is a different purpose from obtaining seeds. */
    public static boolean allowsHand(Material material, Map<String, Integer> enchantments) {
        if (material == Material.SHEARS) return false;
        return enchantments.entrySet().stream().noneMatch(entry -> entry.getValue() > 0
                && (entry.getKey().equals("silk_touch") || entry.getKey().endsWith(":silk_touch")));
    }
}
