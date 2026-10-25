package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.memory.MemoryType;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Observed source choices for planning. World/tool reads must stay on the main thread. */
public final class ResourceOptions {
    private static final Set<Material> STONE_INGREDIENTS = Set.of(Material.COBBLESTONE,
            Material.COBBLED_DEEPSLATE, Material.BLACKSTONE);

    private ResourceOptions() {}

    /**
     * Expected normal stone ingredients from usable remembered sources. An absent source means unknown/unavailable
     * here, not that the world lacks the resource. No chunks are loaded and no hidden blocks or loot tables are read.
     */
    public static Set<Material> stoneDrops(AIPlayer ai) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (Material drop : STONE_INGREDIENTS) {
            if (ai.getWorkBudget().isExhausted()) break;
            Predicate<Block> matches = block -> expectedStoneDrop(ai, block) == drop;
            if (ResourceLocator.locate(ai, MemoryType.STONE, GatherPlans.STONE_WALK_RANGE, matches) != null
                    || ResourceLocator.locateBlocked(ai, MemoryType.STONE, GatherPlans.DIG_TOWARD_RANGE, matches) != null) {
                result.add(drop);
            }
        }
        return Set.copyOf(result);
    }

    public static List<Action> mineStone(AIPlayer ai, Material drop) {
        return GatherPlans.mineStone(ai, drop);
    }

    public static List<Action> findCoal(AIPlayer ai) {
        return GatherPlans.findCoal(ai);
    }

    static boolean isStoneIngredient(Material material) { return STONE_INGREDIENTS.contains(material); }

    /** Uses the same automatic tool choice as the legacy mining leaf, without changing the selected slot. */
    static @Nullable Material expectedStoneDrop(AIPlayer ai, Block block) {
        int slot = ai.getInventory().bestToolSlot(block);
        if (slot < 0) return null;
        ItemStack tool = ai.getPlayer().getInventory().getItem(slot);
        if (tool == null || tool.isEmpty() || !block.isPreferredTool(tool)) return null;
        boolean silk = tool.getEnchantments().entrySet().stream().anyMatch(entry -> entry.getValue() > 0
                && entry.getKey().getKey().toString().equals("minecraft:silk_touch"));
        return stoneDrop(block.getType(), silk);
    }

    /** Narrow vanilla forecast; events, custom loot and subsequent tool changes are checked by actual outcomes. */
    static @Nullable Material stoneDrop(Material source, boolean silkTouch) {
        return switch (source) {
            case STONE -> silkTouch ? Material.STONE : Material.COBBLESTONE;
            case DEEPSLATE -> silkTouch ? Material.DEEPSLATE : Material.COBBLED_DEEPSLATE;
            case COBBLESTONE, COBBLED_DEEPSLATE, BLACKSTONE -> source;
            default -> null;
        };
    }
}
