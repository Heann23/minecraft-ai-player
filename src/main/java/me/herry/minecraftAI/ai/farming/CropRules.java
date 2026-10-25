package me.herry.minecraftAI.ai.farming;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Supported propagation knowledge. Planting spends one item and never grants growth or harvest output. */
public final class CropRules {
    public static final List<Material> SEEDS = List.of(Material.WHEAT_SEEDS, Material.CARROT,
            Material.POTATO, Material.BEETROOT_SEEDS);

    private CropRules() {}

    public static @Nullable Material crop(Material seed) {
        return switch (seed) {
            case WHEAT_SEEDS -> Material.WHEAT;
            case CARROT -> Material.CARROTS;
            case POTATO -> Material.POTATOES;
            case BEETROOT_SEEDS -> Material.BEETROOTS;
            default -> null;
        };
    }

    public static @Nullable Material seed(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            default -> null;
        };
    }

    public static boolean tillable(Material soil) {
        return soil == Material.DIRT || soil == Material.GRASS_BLOCK || soil == Material.DIRT_PATH;
    }

    public static boolean canSpendOne(int held, boolean establishedSameCrop, boolean unknownPlots) {
        if (held < 1) return false;
        if (held > 1) return true;
        return !establishedSameCrop && !unknownPlots;
    }

    public static boolean suitable(boolean emptyAbove, boolean suitableSoil, boolean waterObserved, int light) {
        return emptyAbove && suitableSoil && waterObserved && light >= 9;
    }
}
