package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.InspectCropAction;
import me.herry.minecraftAI.ai.action.PlantCropAction;
import me.herry.minecraftAI.ai.action.TillSoilAction;
import me.herry.minecraftAI.ai.farming.CropRules;
import me.herry.minecraftAI.ai.farming.FarmChecks;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.Ageable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Bounded local irrigation survey and one-item planting plans. Growing is observed, never simulated as food. */
public final class FarmingPlans {
    public enum Stage { PLANTED, MATURE, EMPTY, UNKNOWN }

    public record PlantingSite(UUID world, BlockPoint soil, Material seed, Material crop, boolean needsTilling,
                               boolean hydrated, boolean waterObserved, int light) {}
    public record Crop(UUID world, BlockPoint soil, Material seed, Material crop, Stage stage, int age,
                       int maximumAge, boolean hydrated, boolean waterObserved, int light) {}
    public record Snapshot(List<PlantingSite> plantable, List<Crop> crops, boolean complete) {
        public Snapshot { plantable = List.copyOf(plantable); crops = List.copyOf(crops); }
    }

    private static final int MAX_PLOTS = 16;
    private static final int MAX_SITES = 512;
    private static final int SITES_PER_TICK = 16;
    private static final double LOCAL_RANGE = 18.0;
    private static final long REFRESH_TICKS = 100;
    // Main-thread only. Values retain no AI reference; lifecycle/position/world checks reject stale observations.
    private static final Map<AIPlayer, Survey> SURVEYS = new WeakHashMap<>();

    private FarmingPlans() {}

    public static void invalidate(AIPlayer ai) { SURVEYS.remove(ai); }

    public static boolean searching(AIPlayer ai) {
        Survey survey = SURVEYS.get(ai);
        return survey != null && !survey.queue.isEmpty();
    }

    public static Snapshot observe(AIPlayer ai) {
        Survey survey = SURVEYS.get(ai);
        long now = ai.getTicks();
        String signature = seedSignature(ai);
        if (survey == null || !survey.world.equals(ai.getWorldId()) || survey.center.distanceSq(ai.getPosition()) > 64.0
                || !survey.seedSignature.equals(signature) || survey.queue.isEmpty() && now - survey.completedAt >= REFRESH_TICKS) {
            survey = new Survey(ai, signature);
            SURVEYS.put(ai, survey);
        }
        if (survey.lastAdvancedAt != now) {
            survey.lastAdvancedAt = now;
            survey.advance(ai);
        }
        return survey.snapshot(ai.getPosition());
    }

    /** A first propagation item may establish production; an existing/unknown crop keeps the final reserve. */
    public static boolean allowedToPlant(AIPlayer ai, Material seed) {
        Material crop = CropRules.crop(seed);
        if (crop == null) return false;
        int held = ai.getInventory().count(seed);
        if (held > 1) return true;
        if (held < 1) return false;
        boolean established = false;
        boolean unknown = false;
        int examined = 0;
        World world = ai.getPlayer().getWorld();
        for (var entry : ai.getMemory().all(MemoryType.FARM_PLOT, world.getUID(), ai.getTicks())) {
            if (++examined > MAX_PLOTS || ai.getWorkBudget().isExhausted()) { unknown = true; break; }
            BlockPoint plant = entry.pos().offset(0, 1, 0);
            if (!Positions.isLoaded(world, plant) || !FarmChecks.soilVisible(ai, entry.pos())) { unknown = true; continue; }
            if (Positions.block(world, plant).getType() == crop) established = true;
        }
        return CropRules.canSpendOne(held, established, unknown);
    }

    public static List<Action> planPlant(AIPlayer ai, Material seed) {
        if (!allowedToPlant(ai, seed)) return List.of();
        return observe(ai).plantable().stream().filter(site -> site.seed() == seed)
                .map(site -> plantAt(ai, site.soil(), seed)).filter(actions -> !actions.isEmpty()).findFirst().orElse(List.of());
    }

    /** Revalidates the chosen soil; no fallback to another target or implicit hoe crafting. */
    public static List<Action> plantAt(AIPlayer ai, BlockPoint soil, Material seed) {
        if (!allowedToPlant(ai, seed) || !Positions.isLoaded(ai.getPlayer().getWorld(), soil)
                || !Positions.isLoaded(ai.getPlayer().getWorld(), soil.offset(0, 1, 0))) return List.of();
        World world = ai.getPlayer().getWorld();
        Material type = Positions.block(world, soil).getType();
        boolean needsTilling = type != Material.FARMLAND;
        if (needsTilling && (!CropRules.tillable(type) || ai.getInventory().count(Tag.ITEMS_HOES) == 0)) return List.of();
        if (!CropRules.suitable(Positions.block(world, soil.offset(0, 1, 0)).getType().isAir(),
                type == Material.FARMLAND || CropRules.tillable(type), FarmChecks.waterObserved(ai, soil),
                Positions.block(world, soil.offset(0, 1, 0)).getLightLevel())) return List.of();
        if (!FarmChecks.soilVisible(ai, soil) || ai.getTeam().isClaimedByOther(ai, world.getUID(), soil)
                || ai.getMemory().contains(MemoryType.UNREACHABLE, world.getUID(), soil, ai.getTicks())) return List.of();
        ai.getTeam().claim(ai, soil);
        List<Action> actions = new ArrayList<>();
        actions.add(new MoveToAction(PathGoal.reach(soil, 3.5), false));
        if (needsTilling) actions.add(new TillSoilAction(soil));
        actions.add(new PlantCropAction(soil, seed));
        return actions;
    }

    /** Revisiting changes knowledge only. It neither waits for forced growth nor claims a food yield. */
    public static List<Action> revisit(AIPlayer ai) {
        return ai.getMemory().all(MemoryType.FARM_PLOT, ai.getWorldId(), ai.getTicks()).stream()
                .filter(entry -> entry.pos().distance(ai.getPosition()) > 2.5)
                .filter(entry -> !ai.getMemory().contains(MemoryType.UNREACHABLE, ai.getWorldId(), entry.pos(), ai.getTicks()))
                .sorted(Comparator.comparingDouble(entry -> entry.pos().distanceSq(ai.getPosition())))
                .limit(MAX_PLOTS).findFirst()
                .map(entry -> revisit(ai, entry.pos()))
                .orElse(List.of());
    }

    public static List<Action> revisit(AIPlayer ai, BlockPoint soil) {
        if (!ai.getMemory().contains(MemoryType.FARM_PLOT, ai.getWorldId(), soil, ai.getTicks())
                || ai.getMemory().contains(MemoryType.FARM_CHECKED, ai.getWorldId(), soil, ai.getTicks())
                || ai.getMemory().contains(MemoryType.UNREACHABLE, ai.getWorldId(), soil, ai.getTicks())) return List.of();
        return List.of(new MoveToAction(PathGoal.reach(soil.offset(0, 1, 0), 2.0), false), new InspectCropAction(soil));
    }

    private static String seedSignature(AIPlayer ai) {
        StringBuilder result = new StringBuilder();
        for (Material seed : CropRules.SEEDS) result.append(seed.name()).append('=').append(ai.getInventory().count(seed)).append(';');
        return result.toString();
    }

    private static Crop unknown(UUID world, BlockPoint soil) {
        return new Crop(world, soil, Material.AIR, Material.AIR, Stage.UNKNOWN, -1, -1, false, false, -1);
    }

    private static final class Survey {
        final UUID world;
        final BlockPoint center;
        final String seedSignature;
        final ArrayDeque<BlockPoint> queue = new ArrayDeque<>();
        final Set<BlockPoint> ownPlots = new HashSet<>();
        final Map<BlockPoint, Crop> crops = new LinkedHashMap<>();
        final Map<BlockPoint, List<PlantingSite>> plantable = new LinkedHashMap<>();
        final List<Material> seeds;
        boolean clipped;
        long lastAdvancedAt = Long.MIN_VALUE;
        long completedAt;

        Survey(AIPlayer ai, String signature) {
            world = ai.getWorldId(); center = ai.getPosition(); seedSignature = signature;
            // Future hoe/seed acquisition belongs to the planner; observation must not hide a valid soil site.
            seeds = CropRules.SEEDS;
            Set<BlockPoint> added = new HashSet<>();
            for (var entry : ai.getMemory().all(MemoryType.FARM_PLOT, world, ai.getTicks())) {
                if (ownPlots.size() >= MAX_PLOTS) { clipped = true; break; }
                ownPlots.add(entry.pos()); crops.put(entry.pos(), unknown(world, entry.pos()));
                if (added.add(entry.pos())) queue.addLast(entry.pos());
            }
            var waters = ai.getMemory().all(MemoryType.WATER_SOURCE, world, ai.getTicks()).stream()
                    .filter(entry -> entry.pos().distance(center) <= LOCAL_RANGE)
                    .sorted(Comparator.comparingDouble(entry -> entry.pos().distanceSq(center))).limit(4).toList();
            for (var entry : waters) for (int dy = 0; dy >= -1; dy--) {
                for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                    BlockPoint soil = entry.pos().offset(dx, dy, dz);
                    if (!added.add(soil)) continue;
                    if (queue.size() >= MAX_SITES) { clipped = true; continue; }
                    queue.addLast(soil);
                }
            }
            completedAt = ai.getTicks();
        }

        void advance(AIPlayer ai) {
            if (queue.isEmpty()) return;
            World actualWorld = ai.getPlayer().getWorld();
            int remaining = SITES_PER_TICK;
            var eye = ai.getPlayer().getEyeLocation();
            var opacity = PerceptionSystem.opacityOf(actualWorld);
            while (remaining-- > 0 && !queue.isEmpty() && !ai.getWorkBudget().isExhausted()) {
                BlockPoint soil = queue.removeFirst();
                BlockPoint plant = soil.offset(0, 1, 0);
                if (soil.y() < actualWorld.getMinHeight() || plant.y() >= actualWorld.getMaxHeight()
                        || !Positions.isLoaded(actualWorld, soil) || !Positions.isLoaded(actualWorld, plant)) continue;
                if (!Visibility.canSeeBlock(opacity, eye.getX(), eye.getY(), eye.getZ(), soil.x(), soil.y(), soil.z())
                        && !Visibility.canSeeBlock(opacity, eye.getX(), eye.getY(), eye.getZ(), plant.x(), plant.y(), plant.z())) continue;
                var block = Positions.block(actualWorld, plant);
                Material soilType = Positions.block(actualWorld, soil).getType();
                boolean hydrated = FarmChecks.hydrated(actualWorld, soil);
                boolean waterObserved = FarmChecks.waterObserved(ai, soil);
                int light = block.getLightLevel();
                Material seed = CropRules.seed(block.getType());
                if (ownPlots.contains(soil)) {
                    if (seed != null && block.getBlockData() instanceof Ageable ageable) {
                        crops.put(soil, new Crop(world, soil, seed, block.getType(), ageable.getAge() >= ageable.getMaximumAge()
                                ? Stage.MATURE : Stage.PLANTED, ageable.getAge(), ageable.getMaximumAge(), hydrated, waterObserved, light));
                    } else if (block.getType().isAir()) {
                        crops.put(soil, new Crop(world, soil, Material.AIR, Material.AIR, Stage.EMPTY, -1, -1, hydrated, waterObserved, light));
                    }
                }
                if (soil.distance(center) > LOCAL_RANGE || !CropRules.suitable(block.getType().isAir(),
                        soilType == Material.FARMLAND || CropRules.tillable(soilType), waterObserved, light)) continue;
                if (ai.getTeam().isClaimedByOther(ai, world, soil)
                        || ai.getMemory().contains(MemoryType.UNREACHABLE, world, soil, ai.getTicks())) continue;
                if (soil.x() == ai.getPosition().x() && soil.z() == ai.getPosition().z()) continue;
                List<PlantingSite> sites = new ArrayList<>();
                for (Material propagation : seeds) sites.add(new PlantingSite(world, soil, propagation,
                        CropRules.crop(propagation), soilType != Material.FARMLAND, hydrated, waterObserved, light));
                if (!sites.isEmpty()) plantable.put(soil, List.copyOf(sites));
            }
            if (queue.isEmpty()) completedAt = ai.getTicks();
        }

        Snapshot snapshot(BlockPoint position) {
            List<PlantingSite> sites = plantable.values().stream().flatMap(List::stream)
                    .sorted(Comparator.comparingDouble(site -> site.soil().distanceSq(position))).toList();
            List<Crop> observations = crops.values().stream().sorted(Comparator.comparingDouble(crop -> crop.soil().distanceSq(position))).toList();
            return new Snapshot(sites, observations, queue.isEmpty() && !clipped);
        }
    }
}
