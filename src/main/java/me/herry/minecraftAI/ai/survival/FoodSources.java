package me.herry.minecraftAI.ai.survival;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.jetbrains.annotations.Nullable;

/** Knowledge of supported food blocks, not predicted loot or artificial growth. */
public final class FoodSources {
    private FoodSources() {}

    /** The normal resource family; wheat and kelp still require a separate food-making process. */
    public static @Nullable Material product(Material source) {
        return switch (source) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case MELON -> Material.MELON_SLICE;
            case SWEET_BERRY_BUSH -> Material.SWEET_BERRIES;
            case KELP, KELP_PLANT -> Material.KELP;
            default -> null;
        };
    }

    public static boolean usesInteraction(Material source) {
        return source == Material.SWEET_BERRY_BUSH;
    }

    /** Age is a growth requirement only for crops/berries, not an edible-quantity estimate. */
    public static boolean ready(Material source, int age, int maximumAge) {
        return switch (source) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS -> maximumAge > 0 && age >= maximumAge;
            case SWEET_BERRY_BUSH -> maximumAge >= 3 && age >= 2;
            case MELON, KELP, KELP_PLANT -> true;
            default -> false;
        };
    }

    public static boolean isReady(Block block) {
        if (product(block.getType()) == null) return false;
        if (block.getBlockData() instanceof Ageable ageable) {
            return ready(block.getType(), ageable.getAge(), ageable.getMaximumAge());
        }
        return ready(block.getType(), -1, -1);
    }
}
