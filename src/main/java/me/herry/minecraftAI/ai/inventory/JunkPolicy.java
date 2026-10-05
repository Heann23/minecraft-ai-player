package me.herry.minecraftAI.ai.inventory;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 가 쓰지 않는 아이템(버려도 되는 것)을 고른다. 가방이 차서 필요한 아이템을 줍지 못하게 되는 것을 막기 위해서다.
 * 도구와 장비 재료, 음식, 연료는 남기고, 그 밖의 잡동사니와 너무 많이 쌓인 블록, 더 좋은 것이 있는 낡은 도구를 버린다.
 */
public final class JunkPolicy {
    private enum Pool { STONE, FILLER, LOG, PLANK, STICK, FOOD, TABLE, FURNACE, TORCH, GRAVEL }

    // 종류별로 이만큼까지만 가지고 다닌다. 넘치는 묶음은 버린다.
    private static final Map<Pool, Integer> CAPS = new EnumMap<>(Map.of(
            Pool.STONE, 64,
            Pool.FILLER, 64,
            Pool.LOG, 64,
            Pool.PLANK, 64,
            Pool.STICK, 32,
            Pool.FOOD, 64,
            Pool.TABLE, 1,
            Pool.FURNACE, 1,
            Pool.TORCH, 64,
            // 부싯돌을 얻으려고 놓았다가 다시 캘 자갈
            Pool.GRAVEL, 16
    ));
    // 같은 종류의 도구는 가장 좋은 등급만, 최대 이만큼 남긴다 (하나가 부서져도 바로 바꿔 들 수 있게).
    private static final int MAX_TOOLS_PER_KIND = 2;
    private static final int STORAGE_SIZE = 36;
    private static final List<Tag<Material>> TOOL_KINDS = List.of(
            Tag.ITEMS_PICKAXES, Tag.ITEMS_AXES, Tag.ITEMS_SWORDS, Tag.ITEMS_SHOVELS, Tag.ITEMS_HOES);
    // 지금은 쓰지 않지만 엔더 드래곤까지 가는 길에 필요한 귀한 아이템
    private static final Set<Material> VALUABLE = Set.of(
            Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT, Material.ENDER_PEARL, Material.ENDER_EYE,
            Material.BLAZE_ROD, Material.BLAZE_POWDER, Material.OBSIDIAN, Material.BUCKET, Material.WATER_BUCKET,
            Material.LAVA_BUCKET, Material.FLINT_AND_STEEL, Material.BOW, Material.ARROW, Material.SHIELD,
            Material.REDSTONE, Material.LAPIS_LAZULI, Material.STRING, Material.FLINT, Material.LEATHER);

    private JunkPolicy() {
    }

    /**
     * 버려도 되는 슬롯 목록. 앞쪽 슬롯(핫바)부터 보면서 남길 것을 먼저 채우므로, 손에 익은 핫바의 아이템이 남는다.
     */
    public static List<Integer> junkSlots(PlayerInventory inventory) {
        Map<Tag<Material>, Integer> bestLevel = bestToolLevels(inventory);
        Map<Tag<Material>, Integer> keptTools = new java.util.HashMap<>();
        Map<Pool, Integer> kept = new EnumMap<>(Pool.class);
        List<Integer> junk = new ArrayList<>();
        int workPickaxeLevel = bestWorkPickaxeLevel(inventory);
        boolean keptWorkPickaxe = false;

        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            Material type = item.getType();

            Tag<Material> kind = toolKind(type);
            if (kind != null) {
                int level = ToolTier.of(type).level();
                // 철 이상의 곡괭이가 있어도 돌 이하의 곡괭이 중 가장 좋은 것 하나는 남긴다.
                // 굴을 파는 돌은 그것으로 캐야 귀한 곡괭이가 닳지 않는다.
                boolean workPickaxe = kind == Tag.ITEMS_PICKAXES && !keptWorkPickaxe && level == workPickaxeLevel
                        && HandPolicy.isWorkPickaxe(level, bestLevel.getOrDefault(kind, 0));
                if (workPickaxe) {
                    keptWorkPickaxe = true;
                    continue;
                }
                int count = keptTools.getOrDefault(kind, 0);
                if (level < bestLevel.getOrDefault(kind, 0) || count >= MAX_TOOLS_PER_KIND) junk.add(slot);
                else keptTools.put(kind, count + 1);
                continue;
            }
            if (ItemCategory.of(type) == ItemCategory.ARMOR) {
                // 입고 있는 것보다 좋은 갑옷은 곧 바꿔 입을 것이므로 남긴다. 같거나 못한 것은 바꿔 입고 남은 여분이다.
                if (!isUpgrade(inventory, type)) junk.add(slot);
                continue;
            }

            Pool pool = poolOf(type);
            if (pool == null) {
                if (!isAlwaysKept(type)) junk.add(slot);
                continue;
            }
            int count = kept.getOrDefault(pool, 0);
            if (count >= CAPS.get(pool)) {
                junk.add(slot);
            } else {
                kept.put(pool, count + item.getAmount());
            }
        }
        return junk;
    }

    // 그 부위에 지금 입고 있는 것보다 등급이 높은 갑옷인지
    private static boolean isUpgrade(PlayerInventory inventory, Material type) {
        ArmorSlot slot = ArmorSlot.of(type);
        if (slot == null) return false;
        ItemStack worn = InventorySystem.worn(inventory, slot);
        int wornLevel = worn == null || worn.isEmpty() ? -1 : ArmorTier.of(worn.getType()).level();
        return ArmorTier.of(type).level() > wornLevel;
    }

    private static Map<Tag<Material>, Integer> bestToolLevels(PlayerInventory inventory) {
        Map<Tag<Material>, Integer> best = new java.util.HashMap<>();
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            Tag<Material> kind = toolKind(item.getType());
            if (kind != null) best.merge(kind, ToolTier.of(item.getType()).level(), Math::max);
        }
        return best;
    }

    // 돌 이하의 곡괭이 중 가장 좋은 등급. 없으면 0.
    private static int bestWorkPickaxeLevel(PlayerInventory inventory) {
        int best = 0;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || !Tag.ITEMS_PICKAXES.isTagged(item.getType())) continue;
            int level = ToolTier.of(item.getType()).level();
            if (level <= ToolTier.STONE.level()) best = Math.max(best, level);
        }
        return best;
    }

    private static Tag<Material> toolKind(Material type) {
        for (Tag<Material> kind : TOOL_KINDS) {
            if (kind.isTagged(type)) return kind;
        }
        return null;
    }

    private static Pool poolOf(Material type) {
        if (type == Material.CRAFTING_TABLE) return Pool.TABLE;
        if (type == Material.FURNACE) return Pool.FURNACE;
        if (type == Material.TORCH) return Pool.TORCH;
        if (type == Material.STICK) return Pool.STICK;
        if (type == Material.GRAVEL) return Pool.GRAVEL;
        if (Tag.LOGS.isTagged(type)) return Pool.LOG;
        if (Tag.PLANKS.isTagged(type)) return Pool.PLANK;
        if (InventorySystem.isSafeFood(type)) return Pool.FOOD;
        if (Tag.ITEMS_STONE_TOOL_MATERIALS.isTagged(type)) return Pool.STONE;
        if (InventorySystem.isFiller(type)) return Pool.FILLER;
        return null;
    }

    // 개수와 상관없이 항상 남기는 것: 광물(석탄, 철 원석과 주괴 등)과 앞으로 쓸 귀한 아이템
    private static boolean isAlwaysKept(Material type) {
        if (type == Material.IRON_INGOT || type == Material.COAL || type == Material.CHARCOAL) return true;
        if (ItemCategory.of(type) == ItemCategory.ORE) return true;
        return VALUABLE.contains(type);
    }
}
