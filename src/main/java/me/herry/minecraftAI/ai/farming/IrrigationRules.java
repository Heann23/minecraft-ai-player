package me.herry.minecraftAI.ai.farming;

import org.bukkit.Material;

import java.util.List;

/** Conservative one-cell basin knowledge. Installation never implies instant farmland hydration or food. */
public final class IrrigationRules {
    public enum Cell { SOIL, EMPTY, SOURCE, WALL, OTHER, UNKNOWN }
    public record Basin(Cell center, Cell floor, List<Cell> sides, boolean openAbove,
                        boolean protectedPlace, boolean evaporates, int usefulSoils) {
        public Basin { sides = List.copyOf(sides); }
    }

    private IrrigationRules() {}

    public static boolean excavatable(Material material) {
        return material == Material.DIRT || material == Material.GRASS_BLOCK || material == Material.DIRT_PATH;
    }

    /** Natural full cells only: exclude stairs, slabs, farmland, waterlogging, containers and building materials. */
    public static boolean containment(Material material) {
        return switch (material) {
            case DIRT, GRASS_BLOCK, COARSE_DIRT, ROOTED_DIRT, STONE, DEEPSLATE, GRANITE, DIORITE, ANDESITE, TUFF, CALCITE -> true;
            default -> false;
        };
    }

    public static boolean enclosed(Basin basin) {
        return basin.floor() == Cell.WALL && basin.sides().size() == 4
                && basin.sides().stream().allMatch(side -> side == Cell.WALL);
    }

    public static boolean available(Basin basin) {
        return !basin.evaporates() && !basin.protectedPlace() && basin.openAbove() && enclosed(basin)
                && basin.usefulSoils() >= 2
                && (basin.center() == Cell.SOIL || basin.center() == Cell.EMPTY || basin.center() == Cell.SOURCE);
    }
}
