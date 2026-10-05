package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.ProgressFacts;
import me.herry.minecraftAI.ai.goal.Stage;
import me.herry.minecraftAI.ai.inventory.ArmorSlot;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.world.Base;
import me.herry.minecraftAI.ai.world.WorldModel;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 진행(Milestone)을 어디까지 이뤘는지 확인한다.
 * 판정 자체(next, stageOf)는 ProgressFacts 만 보는 순수 로직이고, facts() 만 Bukkit 에서 값을 읽는다.
 */
public final class Progression {
    // 이보다 멀리 있는 작업대나 화로는 다시 찾아가느니 새로 만드는 편이 낫다.
    private static final double STATION_RANGE = 48.0;

    private Progression() {
    }

    public static @Nullable Milestone next(AIPlayer ai) {
        return next(facts(ai));
    }

    /**
     * 아직 이루지 못한 첫 번째 항목. 전부 이뤘으면(드래곤 처치) null.
     */
    public static @Nullable Milestone next(ProgressFacts facts) {
        if (facts.dragonDefeated) return null;
        for (Milestone milestone : Milestone.values()) {
            if (milestone == Milestone.CRAFTING_TABLE || milestone.isAchieved(facts) || facts.isDeferred(milestone)) continue;
            // 작업대는 다른 것을 만들 때만 필요하다. 만들 것이 남아 있지 않으면 작업대에서 멀어져도 새로 만들지 않는다.
            boolean needsTable = milestone.kind() == Milestone.Kind.CRAFT && !Milestone.CRAFTING_TABLE.isAchieved(facts);
            return needsTable ? Milestone.CRAFTING_TABLE : milestone;
        }
        return null;
    }

    // 지금 속한 큰 단계. 작업대는 어느 단계에서든 다시 필요해질 수 있으므로, 그 뒤에 만들려는 것의 단계로 본다.
    public static Stage stageOf(ProgressFacts facts) {
        if (facts.dragonDefeated) return Stage.CLEARED;
        for (Milestone milestone : Milestone.values()) {
            if (milestone == Milestone.CRAFTING_TABLE || facts.isDeferred(milestone)) continue;
            if (!milestone.isAchieved(facts)) return milestone.stage();
        }
        return Stage.CLEARED;
    }

    public static ProgressFacts facts(AIPlayer ai) {
        InventorySystem inventory = ai.getInventory();
        WorldModel model = ai.getWorldModel();
        ProgressFacts facts = new ProgressFacts();
        facts.pickaxe = inventory.bestTier(Tag.ITEMS_PICKAXES);
        facts.axe = inventory.bestTier(Tag.ITEMS_AXES);
        facts.sword = inventory.bestTier(Tag.ITEMS_SWORDS);
        for (ArmorSlot slot : ArmorSlot.values()) facts.setArmor(slot, inventory.bestArmorTier(slot));
        for (Map.Entry<Material, Integer> entry : inventory.snapshotWithEquipment().entrySet()) {
            facts.setCount(entry.getKey().name(), entry.getValue());
        }
        facts.tableAvailable = inventory.has(Material.CRAFTING_TABLE) || nearbyTable(ai) != null;
        facts.furnaceAvailable = inventory.has(Material.FURNACE) || nearbyFurnace(ai) != null;
        Base home = model.getHome();
        facts.shelterBuilt = home != null && home.isSheltered();
        facts.furnaceAtHome = home != null && home.furnace() != null;
        for (Milestone milestone : Milestone.values()) {
            if (milestone.isOptional() && ai.isDeferred(milestone)) facts.defer(milestone);
        }
        facts.netherPortalBuilt = model.hasNetherPortal();
        facts.strongholdFound = model.getStronghold() != null;
        facts.endPortalReady = model.isEndPortalReady();
        facts.dragonDefeated = model.isDragonDefeated();
        return facts;
    }

    public static Material materialOf(Milestone milestone) {
        return switch (milestone) {
            case CRAFTING_TABLE -> Material.CRAFTING_TABLE;
            case WOODEN_PICKAXE -> Material.WOODEN_PICKAXE;
            case STONE_PICKAXE -> Material.STONE_PICKAXE;
            case STONE_AXE -> Material.STONE_AXE;
            case STONE_SWORD -> Material.STONE_SWORD;
            case FURNACE -> Material.FURNACE;
            case SHELTER -> Material.OAK_DOOR;
            case IRON_PICKAXE -> Material.IRON_PICKAXE;
            case IRON_SWORD -> Material.IRON_SWORD;
            case IRON_CHESTPLATE -> Material.IRON_CHESTPLATE;
            case IRON_LEGGINGS -> Material.IRON_LEGGINGS;
            case IRON_HELMET -> Material.IRON_HELMET;
            case IRON_BOOTS -> Material.IRON_BOOTS;
            case SHIELD -> Material.SHIELD;
            case BUCKET -> Material.BUCKET;
            case DIAMOND_PICKAXE -> Material.DIAMOND_PICKAXE;
            case FLINT_AND_STEEL -> Material.FLINT_AND_STEEL;
            case WATER_BUCKET -> Material.WATER_BUCKET;
            case OBSIDIAN, NETHER_PORTAL -> Material.OBSIDIAN;
            case BLAZE_RODS -> Material.BLAZE_ROD;
            case ENDER_PEARLS -> Material.ENDER_PEARL;
            case EYES_OF_ENDER, STRONGHOLD, END_PORTAL -> Material.ENDER_EYE;
            case END_GEAR -> Material.BOW;
            case ENDER_DRAGON -> Material.DRAGON_EGG;
        };
    }

    // 기억하고 있는 작업대 중 다시 찾아갈 만큼 가까운 것.
    public static @Nullable BlockPoint nearbyTable(AIPlayer ai) {
        return ResourceLocator.locate(ai, MemoryType.WORKBENCH, STATION_RANGE);
    }

    public static @Nullable BlockPoint nearbyFurnace(AIPlayer ai) {
        return ResourceLocator.locate(ai, MemoryType.FURNACE, STATION_RANGE);
    }
}
