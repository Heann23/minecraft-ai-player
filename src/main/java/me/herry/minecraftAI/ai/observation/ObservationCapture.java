package me.herry.minecraftAI.ai.observation;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.ProgressFacts;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.perception.Threat;
import me.herry.minecraftAI.ai.plan.Progression;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 지금의 AI 를 Observation 으로 옮겨 적는다. Bukkit 객체를 읽는 일은 여기서 끝낸다 (서버 메인 스레드에서만 부른다).
 * 이미 계산해 둔 인식 결과(Perception)와 상황 요약(Situation)을 옮겨 적는 것이 대부분이라서 월드를 새로 훑지는 않는다.
 */
public final class ObservationCapture {
    private ObservationCapture() {
    }

    /**
     * @param situation 이번 판단에 쓴 상황 요약
     * @param task      이번 판단을 하기 직전에 하고 있던 일
     */
    public static Observation capture(AIPlayer ai, Situation situation, CurrentTaskState task) {
        return new Observation(Observation.SCHEMA_VERSION, ai.getTicks(), player(ai, situation), inventory(ai, situation),
                environment(ai, situation), progress(ai, situation), memory(ai, situation), task);
    }

    private static PlayerState player(AIPlayer ai, Situation s) {
        Player player = ai.getPlayer();
        Perception perception = ai.getPerception();
        Location location = player.getLocation();
        AttributeInstance armor = player.getAttribute(Attribute.ARMOR);

        PlayerInventory inventory = player.getInventory();
        Map<String, String> equipment = new LinkedHashMap<>();
        putItem(equipment, "HEAD", inventory.getHelmet());
        putItem(equipment, "CHEST", inventory.getChestplate());
        putItem(equipment, "LEGS", inventory.getLeggings());
        putItem(equipment, "FEET", inventory.getBoots());
        putItem(equipment, "MAIN_HAND", inventory.getItemInMainHand());
        putItem(equipment, "OFF_HAND", inventory.getItemInOffHand());

        List<String> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) effects.add(effect.getType().getKey().getKey());

        return new PlayerState(location.getX(), location.getY(), location.getZ(), perception.getYaw(), perception.getPitch(),
                dimensionOf(player.getWorld()), s.health, s.maxHealth, s.healthState.name(), s.food, perception.getSaturation(),
                s.shouldEat, perception.getAir(), perception.getMaxAir(), armor == null ? 0.0 : armor.getValue(),
                ai.getBody().isGrounded(), perception.isInWater(), s.inLava, perception.isOnFire(), s.standingInDanger,
                s.suffocating, s.drowning, equipment, effects);
    }

    private static void putItem(Map<String, String> equipment, String slot, @Nullable ItemStack item) {
        if (item != null && !item.isEmpty()) equipment.put(slot, item.getType().name());
    }

    private static String dimensionOf(World world) {
        return switch (world.getEnvironment()) {
            case NORMAL -> PlayerState.OVERWORLD;
            case NETHER -> PlayerState.NETHER;
            case THE_END -> PlayerState.THE_END;
            default -> PlayerState.CUSTOM;
        };
    }

    private static InventoryState inventory(AIPlayer ai, Situation s) {
        InventorySystem inventory = ai.getInventory();
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<Material, Integer> entry : inventory.snapshotWithEquipment().entrySet()) {
            counts.put(entry.getKey().name(), entry.getValue());
        }
        return new InventoryState(counts, ai.getPlayer().getInventory().getHeldItemSlot(), s.emptySlots, s.junkSlots,
                s.plankEquivalent, s.cobblestone, s.coal, s.foodCount, s.rawFood, s.hasFuel,
                inventory.bestTier(Tag.ITEMS_PICKAXES).name(), inventory.bestTier(Tag.ITEMS_AXES).name(),
                inventory.bestTier(Tag.ITEMS_SWORDS).name());
    }

    private static EnvironmentState environment(AIPlayer ai, Situation s) {
        Perception perception = ai.getPerception();
        Location location = ai.getPlayer().getLocation();
        Block feet = location.getBlock();
        return new EnvironmentState(perception.getTime(), s.night, perception.isStorm(), perception.isThundering(),
                feet.getBiome().getKey().getKey(), feet.getLightLevel(), s.underground, s.sealedIn, perception.isLavaNearby(),
                perception.isCliffAhead(), s.hostileNearby, s.combat.name(), s.allyNeedsHelp, perception.getHostiles().size(),
                perception.getAnimals().size(), perception.getDrops().size(), entities(ai, perception, location));
    }

    // 몬스터, 동물, 다른 플레이어를 가까운 순으로 추린다.
    private static List<EntitySummary> entities(AIPlayer ai, Perception perception, Location from) {
        List<EntitySummary> result = new ArrayList<>();
        for (Threat threat : perception.getThreats()) {
            if (!threat.type().isHostileMob() || threat.entity() == null) continue;
            result.add(summary(threat.entity(), from, true, threat.targetingMe()));
        }
        for (LivingEntity animal : perception.getAnimals()) result.add(summary(animal, from, false, false));
        for (Player other : perception.getPlayers()) {
            if (!other.getUniqueId().equals(ai.getPlayer().getUniqueId())) result.add(summary(other, from, false, false));
        }
        result.sort(Comparator.comparingDouble(EntitySummary::distance));
        return result.size() > EnvironmentState.MAX_ENTITIES ? result.subList(0, EnvironmentState.MAX_ENTITIES) : result;
    }

    private static EntitySummary summary(LivingEntity entity, Location from, boolean hostile, boolean targetingMe) {
        Location at = entity.getLocation();
        double dx = at.getX() - from.getX();
        double dy = at.getY() - from.getY();
        double dz = at.getZ() - from.getZ();
        return new EntitySummary(entity.getType().name(), dx, dy, dz, Math.sqrt(dx * dx + dy * dy + dz * dz), entity.getHealth(),
                hostile, targetingMe);
    }

    private static ProgressState progress(AIPlayer ai, Situation s) {
        ProgressFacts facts = Progression.facts(ai);
        List<String> achieved = new ArrayList<>();
        for (Milestone milestone : Milestone.values()) {
            if (milestone.isAchieved(facts)) achieved.add(milestone.name());
        }
        return new ProgressState(s.stage.name(), s.nextMilestone == null ? "" : s.nextMilestone.name(), s.need.name(), achieved,
                facts.tableAvailable, facts.furnaceAvailable, facts.shelterBuilt, facts.netherPortalBuilt, facts.strongholdFound,
                facts.endPortalReady, facts.dragonDefeated);
    }

    private static MemoryState memory(AIPlayer ai, Situation s) {
        MemorySystem memory = ai.getMemory();
        UUID world = ai.getWorldId();
        long now = ai.getTicks();
        Map<String, Integer> places = new LinkedHashMap<>();
        for (MemoryType type : MemoryType.values()) {
            int count = memory.count(type, world, now);
            if (count > 0) places.put(type.name(), count);
        }
        return new MemoryState(s.homeKnown, s.homeDistance, s.insideHome, s.homeSheltered, s.chestAvailable, s.canSleep,
                s.knowsTree, s.knowsCoal, s.knowsIron, s.knowsDiamond, s.knowsLootChest, s.furnaceBusy, places,
                memory.getFailures().size(), memory.getLastGoal());
    }
}
