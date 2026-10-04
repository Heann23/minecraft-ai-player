package me.herry.minecraftAI.ai.inventory;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import me.herry.minecraftAI.ai.AIBody;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * AI 의 인벤토리를 분석하고 손에 드는 아이템을 바꾼다.
 */
public final class InventorySystem {
    private static final int HOTBAR_SIZE = 9;
    private static final int STORAGE_SIZE = 36;
    private static final double HAND_POWER = ToolTier.NONE.combatPower();

    private final AIBody body;

    public InventorySystem(AIBody body) {
        this.body = body;
    }

    public int count(Material material) {
        return count(type -> type == material);
    }

    public int count(ItemCategory category) {
        return count(type -> ItemCategory.of(type) == category);
    }

    public int count(Tag<Material> tag) {
        return count(tag::isTagged);
    }

    public int count(Predicate<Material> filter) {
        PlayerInventory inventory = inventory();
        int total = 0;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty() && filter.test(item.getType())) total += item.getAmount();
        }
        return total;
    }

    public boolean has(Material material) {
        return findSlot(item -> item.getType() == material) >= 0;
    }

    public int emptySlots() {
        PlayerInventory inventory = inventory();
        int empty = 0;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) empty++;
        }
        return empty;
    }

    public boolean isFull() {
        return emptySlots() == 0;
    }

    // 조건에 맞는 첫 슬롯. 없으면 -1.
    public int findSlot(Predicate<ItemStack> filter) {
        PlayerInventory inventory = inventory();
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty() && filter.test(item)) return slot;
        }
        return -1;
    }

    /**
     * 해당 블록을 가장 빨리 캘 수 있는 도구의 슬롯. 맨손보다 나은 도구가 없으면 -1.
     */
    public int bestToolSlot(Block block) {
        BlockData data = block.getBlockData();
        PlayerInventory inventory = inventory();
        float handSpeed = data.getDestroySpeed(ItemStack.empty(), true);
        List<HandPolicy.ToolOption> options = new ArrayList<>();
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            float speed = data.getDestroySpeed(item, true);
            // 맨손보다 빠르지 않은 것은 도구로 치지 않는다 (대부분의 아이템이 그렇다).
            if (speed <= handSpeed) continue;
            options.add(new HandPolicy.ToolOption(slot, speed, ToolTier.of(item.getType()).level(), block.isPreferredTool(item)));
        }
        // 캘 수 있는 것 중 가장 싼 도구를 쓴다 (돌은 돌 곡괭이로, 철 곡괭이는 아껴 둔다).
        return HandPolicy.toolSlot(options, handSpeed);
    }

    // 몬스터와 싸울 때 가장 강한 무기의 슬롯. 무기로 쓸 만한 것이 없으면 -1.
    public int bestWeaponSlot() {
        return bestWeaponSlot(HandPolicy.Purpose.FIGHT);
    }

    // 그 일에 쓸 가장 강한 무기의 슬롯. 맨손보다 나은 것이 없으면 -1.
    public int bestWeaponSlot(HandPolicy.Purpose purpose) {
        PlayerInventory inventory = inventory();
        int bestSlot = -1;
        double bestPower = HAND_POWER;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            double power = HandPolicy.weaponPower(item.getType().name(), purpose);
            if (power > bestPower) {
                bestPower = power;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    // 가진 것 중 가장 강한 무기의 상대적 공격력. 무기가 없으면 맨손 값이다.
    public double weaponPower() {
        int slot = bestWeaponSlot();
        if (slot < 0) return HAND_POWER;
        ItemStack item = inventory().getItem(slot);
        return item == null ? HAND_POWER : weaponPower(item.getType());
    }

    public static double weaponPower(Material material) {
        return HandPolicy.weaponPower(material.name(), HandPolicy.Purpose.FIGHT);
    }

    // 해당 종류의 도구 중 가장 좋은 등급. 하나도 없으면 NONE.
    public ToolTier bestTier(Tag<Material> toolTag) {
        PlayerInventory inventory = inventory();
        ToolTier best = ToolTier.NONE;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || !toolTag.isTagged(item.getType())) continue;
            ToolTier tier = ToolTier.of(item.getType());
            if (tier.level() > best.level()) best = tier;
        }
        return best;
    }

    // 굴을 계속 팔 돌/구리 곡괭이가 있는지. 나무 곡괭이는 보충을 막지 않는다.
    public boolean hasWorkPickaxe() {
        return findSlot(item -> Tag.ITEMS_PICKAXES.isTagged(item.getType())
                && HandPolicy.isAdequateWorkPickaxe(ToolTier.of(item.getType()).level())) >= 0;
    }

    // 먹을 음식 중 허기를 가장 많이 채워 주는 것(구운 고기 등)의 슬롯. 날고기는 익힌 음식이 없을 때만 먹게 된다.
    public int bestFoodSlot() {
        PlayerInventory inventory = inventory();
        int bestSlot = -1;
        double bestValue = -1.0;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || !isSafeFood(item.getType())) continue;
            double value = foodValue(item);
            if (value > bestValue) {
                bestValue = value;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static double foodValue(ItemStack item) {
        FoodProperties food = item.getData(DataComponentTypes.FOOD);
        if (food == null) return 0.0;
        return food.nutrition() + food.saturation();
    }

    // 화로에 구우면 더 좋은 음식이 되는 날것인지
    public static boolean isRawFood(Material material) {
        return switch (material) {
            case BEEF, PORKCHOP, CHICKEN, MUTTON, RABBIT, COD, SALMON, POTATO -> true;
            default -> false;
        };
    }

    // 가진 날것 중 가장 많은 종류. 없으면 null.
    public @Nullable Material mostRawFood() {
        Map<Material, Integer> stock = snapshot();
        Material best = null;
        int bestCount = 0;
        for (Map.Entry<Material, Integer> entry : stock.entrySet()) {
            if (isRawFood(entry.getKey()) && entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /**
     * 다리를 놓거나 발판을 쌓을 때 쓸 블록의 슬롯. 흙처럼 흔한 블록을 먼저 쓰고, 도구 재료인 조약돌은 나중에 쓴다. 없으면 -1.
     */
    public int findFillerSlot() {
        int stone = -1;
        PlayerInventory inventory = inventory();
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || !isFiller(item.getType())) continue;
            if (ItemCategory.of(item.getType()) == ItemCategory.BLOCK) return slot;
            if (stone < 0) stone = slot;
        }
        return stone;
    }

    public int countFiller() {
        return count(InventorySystem::isFiller);
    }

    // 발판이나 물막이로 쓸 만한 블록인지. 돌, 흙처럼 흔하고 꽉 찬 블록만 쓴다. 떨어지는 모래나 자갈은 쓰지 않는다.
    public static boolean isFiller(Material material) {
        if (!material.isBlock() || !material.isOccluding() || material.hasGravity()) return false;
        // 양털은 침대를 만들 재료라서 벽이나 발판으로 써 버리지 않는다.
        if (Tag.LOGS.isTagged(material) || Tag.PLANKS.isTagged(material) || Tag.WOOL.isTagged(material)
                || material == Material.CRAFTING_TABLE || material == Material.FURNACE) return false;
        ItemCategory category = ItemCategory.of(material);
        return category == ItemCategory.STONE || category == ItemCategory.BLOCK;
    }

    public boolean hasFood() {
        return bestFoodSlot() >= 0;
    }

    // 먹으면 독이나 허기 효과가 걸리거나 순간이동하는 음식은 먹지 않는다.
    public static boolean isSafeFood(Material material) {
        if (!material.isEdible()) return false;
        return switch (material) {
            case ROTTEN_FLESH, SPIDER_EYE, POISONOUS_POTATO, PUFFERFISH, CHORUS_FRUIT, SUSPICIOUS_STEW -> false;
            default -> true;
        };
    }

    /**
     * 해당 슬롯의 아이템을 손에 든다. 핫바 밖의 아이템은 지금 들고 있는 슬롯과 맞바꾼다.
     */
    public boolean equipSlot(int slot) {
        if (slot < 0 || slot >= STORAGE_SIZE) return false;
        PlayerInventory inventory = inventory();
        if (slot < HOTBAR_SIZE) {
            inventory.setHeldItemSlot(slot);
            return true;
        }

        int held = inventory.getHeldItemSlot();
        ItemStack fromStorage = inventory.getItem(slot);
        ItemStack fromHand = inventory.getItem(held);
        inventory.setItem(held, fromStorage);
        inventory.setItem(slot, fromHand);
        return true;
    }

    public boolean equip(Material material) {
        return equipSlot(findSlot(item -> item.getType() == material));
    }

    /**
     * 닳는 도구를 손에서 내려놓는다. 도구는 도움이 안 되는 일에 써도(곡괭이로 흙 캐기, 동물 때리기) 내구도가 줄어서,
     * 그런 일은 맨손이나 닳지 않는 아이템을 든 채로 한다.
     */
    public void restHand() {
        PlayerInventory inventory = inventory();
        boolean[] wearsOut = new boolean[STORAGE_SIZE];
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            wearsOut[slot] = item != null && !item.isEmpty() && item.hasData(DataComponentTypes.MAX_DAMAGE);
        }
        int held = inventory.getHeldItemSlot();
        int slot = HandPolicy.restSlot(wearsOut, held);
        if (slot >= 0 && slot != held) equipSlot(slot);
    }

    // 음식 아이템의 총 개수 (먹으면 해로운 것은 빼고).
    public int foodCount() {
        return count(InventorySystem::isSafeFood);
    }

    // 화로의 연료로 쓸 수 있는 것을 가지고 있는지.
    public boolean hasFuel() {
        return findSlot(item -> isFuel(item.getType())) >= 0;
    }

    public static boolean isFuel(Material material) {
        return material == Material.COAL || material == Material.CHARCOAL
                || Tag.PLANKS.isTagged(material) || Tag.LOGS.isTagged(material);
    }

    /**
     * 그 부위에 입고 있거나 가방에 가지고 있는 갑옷 중 가장 좋은 등급. 하나도 없으면 NONE.
     * "그 부위에 뭔가 있는가"가 아니라 등급을 돌려주므로, 가죽 갑옷을 철 갑옷으로 착각하지 않는다.
     */
    public ArmorTier bestArmorTier(ArmorSlot slot) {
        PlayerInventory inventory = inventory();
        ArmorTier best = tierOf(worn(inventory, slot), slot);
        for (int index = 0; index < STORAGE_SIZE; index++) {
            ArmorTier tier = tierOf(inventory.getItem(index), slot);
            if (tier.level() > best.level()) best = tier;
        }
        return best;
    }

    /**
     * 가방에 있는 갑옷이 입고 있는 것보다 좋으면 바꿔 입는다. 벗은 갑옷은 그 자리(가방)에 남는다.
     * 방패가 있으면 왼손에 든다.
     *
     * @return 무엇이든 바꿔 입었으면 true
     */
    public boolean wearBestArmor() {
        PlayerInventory inventory = inventory();
        boolean changed = false;
        for (ArmorSlot slot : ArmorSlot.values()) {
            ItemStack current = worn(inventory, slot);
            // 빈 부위는 어떤 갑옷이든 입는다. 등급이 같으면 굳이 바꾸지 않는다.
            int bestLevel = isEmpty(current) ? -1 : ArmorTier.of(current.getType()).level();
            int bestIndex = -1;
            for (int index = 0; index < STORAGE_SIZE; index++) {
                ItemStack item = inventory.getItem(index);
                if (isEmpty(item) || ArmorSlot.of(item.getType()) != slot) continue;
                int level = ArmorTier.of(item.getType()).level();
                if (level > bestLevel) {
                    bestLevel = level;
                    bestIndex = index;
                }
            }
            if (bestIndex < 0) continue;

            ItemStack better = inventory.getItem(bestIndex);
            inventory.setItem(bestIndex, isEmpty(current) ? null : current);
            setWorn(inventory, slot, better);
            changed = true;
        }

        if (isEmpty(inventory.getItemInOffHand())) {
            int shield = findSlot(item -> item.getType() == Material.SHIELD);
            if (shield >= 0) {
                inventory.setItemInOffHand(inventory.getItem(shield));
                inventory.setItem(shield, null);
                changed = true;
            }
        }
        return changed;
    }

    public static @Nullable ItemStack worn(PlayerInventory inventory, ArmorSlot slot) {
        return switch (slot) {
            case HEAD -> inventory.getHelmet();
            case CHEST -> inventory.getChestplate();
            case LEGS -> inventory.getLeggings();
            case FEET -> inventory.getBoots();
        };
    }

    private static void setWorn(PlayerInventory inventory, ArmorSlot slot, ItemStack item) {
        switch (slot) {
            case HEAD -> inventory.setHelmet(item);
            case CHEST -> inventory.setChestplate(item);
            case LEGS -> inventory.setLeggings(item);
            case FEET -> inventory.setBoots(item);
        }
    }

    private static ArmorTier tierOf(@Nullable ItemStack item, ArmorSlot slot) {
        if (isEmpty(item) || ArmorSlot.of(item.getType()) != slot) return ArmorTier.NONE;
        return ArmorTier.of(item.getType());
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.isEmpty();
    }

    // 제작 가능 여부를 계산할 때 쓰는 아이템별 보유 개수.
    public Map<Material, Integer> snapshot() {
        Map<Material, Integer> stock = new HashMap<>();
        PlayerInventory inventory = inventory();
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty()) stock.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return stock;
    }

    // 가방뿐 아니라 입고 있는 갑옷과 왼손에 든 것까지 합친 보유 개수. 진행 판정에 쓴다 (방패는 왼손에 든다).
    public Map<Material, Integer> snapshotWithEquipment() {
        Map<Material, Integer> stock = snapshot();
        PlayerInventory inventory = inventory();
        for (ItemStack armor : inventory.getArmorContents()) {
            if (!isEmpty(armor)) stock.merge(armor.getType(), armor.getAmount(), Integer::sum);
        }
        ItemStack offHand = inventory.getItemInOffHand();
        if (!isEmpty(offHand)) stock.merge(offHand.getType(), offHand.getAmount(), Integer::sum);
        return stock;
    }

    // 가진 나무를 판자 개수로 환산한 값 (원목 1 = 판자 4, 막대기 2 = 판자 1).
    public int plankEquivalent() {
        return count(Tag.LOGS) * 4 + count(Tag.PLANKS) + count(Material.STICK) / 2;
    }

    private PlayerInventory inventory() {
        return body.getPlayer().getInventory();
    }
}
