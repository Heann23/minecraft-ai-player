package me.herry.minecraftAI.ai.inventory;

import org.bukkit.Material;

/** Keep obtained food ingredients/propagules available for later recipes and farming capabilities. */
public final class ForageReserve {
    private ForageReserve() {}

    public static int minimum(Material material) {
        return switch (material) {
            case WHEAT -> 16;
            case KELP -> 32;
            case WHEAT_SEEDS, BEETROOT_SEEDS, MELON_SEEDS, PUMPKIN_SEEDS -> 8;
            default -> 0;
        };
    }
}
