package me.herry.minecraftAI.ai.build;

import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.inventory.ItemCategory;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 설계도의 역할(BlockRole)과 실제 블록/아이템 사이를 이어 준다:
 * 그 칸이 이미 역할대로 되어 있는지, 그 역할에 쓸 아이템이 가방에 있는지.
 */
public final class BuildMaterials {
    private static final int STORAGE_SIZE = 36;

    private BuildMaterials() {
    }

    /**
     * 그 칸이 이미 설계도대로 되어 있는지. 벽 자리에 원래 있던 흙처럼, 누가 놓았든 역할을 하는 블록이면 된 것으로 본다.
     */
    public static boolean isDone(Material type, BlockRole role) {
        return switch (role) {
            // 실내는 걸어 다닐 수 있으면 된다. 이미 들여놓은 시설은 치우지 않는다.
            case CLEAR -> BukkitTerrainView.classify(type) == BlockClass.OPEN || isFixture(type);
            case FLOOR, WALL, ROOF -> BukkitTerrainView.classify(type) == BlockClass.SOLID;
            case DOOR -> Tag.DOORS.isTagged(type);
            case WORKBENCH -> type == Material.CRAFTING_TABLE;
            case CHEST -> type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
            case FURNACE -> type == Material.FURNACE || type == Material.BLAST_FURNACE || type == Material.SMOKER;
            case TORCH -> type == Material.TORCH || type == Material.WALL_TORCH || type == Material.LANTERN;
            case BED -> Tag.BEDS.isTagged(type);
        };
    }

    private static boolean isFixture(Material type) {
        return type == Material.CRAFTING_TABLE || type == Material.CHEST || type == Material.FURNACE
                || Tag.BEDS.isTagged(type) || Tag.DOORS.isTagged(type);
    }

    // 그 역할에 쓸 수 있는 아이템인지
    public static boolean fits(Material item, BlockRole role) {
        return switch (role) {
            case CLEAR -> false;
            case FLOOR, WALL, ROOF -> InventorySystem.isFiller(item);
            case DOOR -> Tag.WOODEN_DOORS.isTagged(item);
            case WORKBENCH -> item == Material.CRAFTING_TABLE;
            case CHEST -> item == Material.CHEST;
            case FURNACE -> item == Material.FURNACE;
            case TORCH -> item == Material.TORCH;
            case BED -> Tag.BEDS.isTagged(item);
        };
    }

    /**
     * 그 역할에 쓸 아이템이 든 칸. 없으면 -1.
     * 벽과 지붕은 돌(조약돌)을 먼저 쓰고, 다리나 발판에 쓰기 좋은 흙은 나중에 쓴다.
     */
    public static int slotFor(PlayerInventory inventory, BlockRole role) {
        int fallback = -1;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || !fits(item.getType(), role)) continue;
            if (!role.isStructural() || ItemCategory.of(item.getType()) == ItemCategory.STONE) return slot;
            if (fallback < 0) fallback = slot;
        }
        return fallback;
    }

    // 벽, 지붕, 바닥에 쓸 수 있는 블록의 총 개수
    public static int structuralCount(PlayerInventory inventory) {
        int total = 0;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty() && InventorySystem.isFiller(item.getType())) total += item.getAmount();
        }
        return total;
    }
}
