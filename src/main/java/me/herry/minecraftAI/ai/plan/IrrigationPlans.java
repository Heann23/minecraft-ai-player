package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.FillBucketAction;
import me.herry.minecraftAI.ai.action.IrrigateCellAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.farming.CropRules;
import me.herry.minecraftAI.ai.farming.IrrigationRules;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Surveys only a bounded, loaded local area. A source installed in an enclosed basin is the only promised outcome. */
public final class IrrigationPlans {
    public record Site(UUID world, BlockPoint cell, boolean needsExcavation, int benefit, int light) {}
    public record Snapshot(List<Site> sites, boolean complete, boolean encounteredUnknown) {
        public Snapshot { sites = List.copyOf(sites); }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final List<BlockPoint> OFFSETS = offsets();
    private static final int PER_TICK = 6;
    private static final int MAX_SITES = 8;
    private static final long REFRESH_TICKS = 100;
    private static final Map<AIPlayer, Survey> SURVEYS = new WeakHashMap<>();

    private IrrigationPlans() {}

    public static void invalidate(AIPlayer ai) { SURVEYS.remove(ai); }
    public static boolean searching(AIPlayer ai) {
        Survey survey = SURVEYS.get(ai);
        return survey != null && survey.cursor < OFFSETS.size();
    }

    public static Snapshot observe(AIPlayer ai) {
        Survey survey = SURVEYS.get(ai);
        long now = ai.getTicks();
        if (survey == null || !survey.world.equals(ai.getWorldId()) || survey.center.distanceSq(ai.getPosition()) > 16
                || survey.cursor >= OFFSETS.size() && now - survey.finishedAt >= REFRESH_TICKS) {
            survey = new Survey(ai); SURVEYS.put(ai, survey);
        }
        if (survey.lastAdvancedAt != now) { survey.lastAdvancedAt = now; survey.advance(ai); }
        return new Snapshot(survey.sites, survey.cursor >= OFFSETS.size(), survey.unknown);
    }

    public static List<Action> plan(AIPlayer ai, BlockPoint cell) {
        if (!ai.getInventory().has(Material.WATER_BUCKET) || !validate(ai, cell, false).isEmpty()) return List.of();
        BlockPoint stance = stance(ai, cell);
        if (stance == null) return List.of();
        ai.getTeam().claim(ai, cell);
        return List.of(new MoveToAction(PathGoal.arrive(stance, 0.2), false),
                new IrrigateCellAction(ai.getWorldId(), cell));
    }

    /** Same geometry/protection contract for the survey, plan and executor; empty means currently supported. */
    public static String validate(AIPlayer ai, BlockPoint cell, boolean requireReach) {
        World world = ai.getPlayer().getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return "irrigation dimension unsupported";
        if (cell.y() <= world.getMinHeight() + 1 || cell.y() + 2 >= world.getMaxHeight()) return "height unsupported";
        if (cell.distanceSq(ai.getPosition()) > 18 * 18) return "irrigation site too distant";
        if (!neighborhoodLoaded(world, cell)) return "irrigation neighborhood not loaded";
        if (protectedPlace(ai, cell)) return "protected terrain or facility";
        if (ai.getTeam().isClaimedByOther(ai, world.getUID(), cell)
                || ai.getMemory().contains(MemoryType.UNREACHABLE, world.getUID(), cell, ai.getTicks())) return "site temporarily unavailable";
        Material center = Positions.block(world, cell).getType();
        IrrigationRules.Cell centerKind = FillBucketAction.isSource(Positions.block(world, cell)) ? IrrigationRules.Cell.SOURCE
                : center.isAir() ? IrrigationRules.Cell.EMPTY : IrrigationRules.excavatable(center) ? IrrigationRules.Cell.SOIL : IrrigationRules.Cell.OTHER;
        List<IrrigationRules.Cell> banks = new ArrayList<>();
        int useful = 0;
        for (int[] side : SIDES) {
            BlockPoint bank = cell.offset(side[0], 0, side[1]);
            Material material = Positions.block(world, bank).getType();
            banks.add(IrrigationRules.containment(material) ? IrrigationRules.Cell.WALL : IrrigationRules.Cell.OTHER);
            if (!Positions.block(world, bank.offset(0, 1, 0)).getType().isAir()
                    || !Positions.block(world, bank.offset(0, 2, 0)).getType().isAir()) return "occupied basin bank";
            if (CropRules.tillable(material) && Positions.block(world, bank.offset(0, 1, 0)).getLightLevel() >= 9) useful++;
        }
        var basin = new IrrigationRules.Basin(centerKind,
                IrrigationRules.containment(Positions.block(world, cell.offset(0, -1, 0)).getType()) ? IrrigationRules.Cell.WALL : IrrigationRules.Cell.OTHER,
                banks, Positions.block(world, cell.offset(0, 1, 0)).getType().isAir()
                && Positions.block(world, cell.offset(0, 2, 0)).getType().isAir(), false, false, useful);
        if (!IrrigationRules.available(basin)) return "unsupported or uncontained basin";
        if (!visible(ai, cell, centerKind == IrrigationRules.Cell.EMPTY ? cell.offset(0, -1, 0) : cell)) return "basin not visible";
        if (requireReach) {
            if (!ai.getBody().isUsable() || ai.getPlayer().isInWater() || !ai.getBody().isGrounded()) return "stable dry footing required";
            BlockPoint feet = ai.getPosition();
            if (feet.x() == cell.x() && feet.z() == cell.z()) return "standing on excavation cell";
            if (ai.getPlayer().getEyeLocation().distance(Positions.center(world, cell)) > 4.5) return "out of reach";
        }
        return "";
    }

    public static boolean sourcePresent(AIPlayer ai, BlockPoint cell) {
        World world = ai.getPlayer().getWorld();
        return Positions.isLoaded(world, cell) && FillBucketAction.isSource(Positions.block(world, cell));
    }

    public static boolean observedSource(AIPlayer ai, BlockPoint cell) {
        return sourcePresent(ai, cell) && visible(ai, cell, cell);
    }

    public static void rememberSource(AIPlayer ai, BlockPoint cell) {
        if (!sourcePresent(ai, cell) || !visible(ai, cell, cell)) return;
        ai.getMemory().remember(MemoryType.WATER_SOURCE, ai.getWorldId(), cell, ai.getTicks(), 6000);
        FarmingPlans.invalidate(ai); invalidate(ai);
    }

    private static @Nullable BlockPoint stance(AIPlayer ai, BlockPoint cell) {
        World world = ai.getPlayer().getWorld();
        BlockPoint best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int[] side : SIDES) {
            BlockPoint point = cell.offset(side[0], 1, side[1]);
            if (!Positions.isLoaded(world, point.offset(0, 1, 0))
                    || !Positions.block(world, point).getType().isAir() || !Positions.block(world, point.offset(0, 1, 0)).getType().isAir()) continue;
            double value = point.distanceSq(ai.getPosition());
            if (value < distance) { best = point; distance = value; }
        }
        return best;
    }

    private static boolean visible(AIPlayer ai, BlockPoint cell, BlockPoint target) {
        var eye = ai.getPlayer().getEyeLocation();
        return Visibility.canSeeBlock(PerceptionSystem.opacityOf(ai.getPlayer().getWorld()),
                eye.getX(), eye.getY(), eye.getZ(), target.x(), target.y(), target.z());
    }

    private static boolean neighborhoodLoaded(World world, BlockPoint cell) {
        if (!Positions.isLoaded(world, cell.offset(0, -1, 0)) || !Positions.isLoaded(world, cell.offset(0, 2, 0))) return false;
        for (int[] side : SIDES) if (!Positions.isLoaded(world, cell.offset(side[0], 0, side[1]))) return false;
        return true;
    }

    private static boolean protectedPlace(AIPlayer ai, BlockPoint cell) {
        UUID world = ai.getWorldId();
        var home = ai.getWorldModel().homeIn(world);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            BlockPoint point = cell.offset(dx, 0, dz);
            if (home != null && (home.isInsideBuilding(world, point) || home.isInsideBuilding(world, point.offset(0, 1, 0)))) return true;
            if (ai.getTeam().getShafts().isStep(world, point) || ai.getTeam().isClaimedByOther(ai, world, point)) return true;
        }
        var job = ai.getBuildJob();
        if (job != null && job.world().equals(world) && inside(cell, job.min(), job.max(), 2)) return true;
        var furnace = ai.getFurnaceJob();
        if (furnace != null && furnace.world().equals(world) && furnace.pos().distanceSq(cell) <= 16) return true;
        for (MemoryType type : List.of(MemoryType.WORKBENCH, MemoryType.OWN_WORKBENCH, MemoryType.FURNACE,
                MemoryType.OWN_FURNACE, MemoryType.BED, MemoryType.PORTAL_SITE, MemoryType.LOOT_CHEST)) {
            if (ai.getMemory().nearest(type, world, cell, ai.getTicks(), entry -> entry.pos().distanceSq(cell) <= 16).isPresent()) return true;
        }
        return ai.getMemory().nearest(MemoryType.FARM_PLOT, world, cell, ai.getTicks(), entry -> entry.pos().distanceSq(cell) <= 2).isPresent();
    }

    private static boolean inside(BlockPoint point, BlockPoint min, BlockPoint max, int margin) {
        return point.x() >= min.x() - margin && point.x() <= max.x() + margin
                && point.y() >= min.y() - margin && point.y() <= max.y() + margin
                && point.z() >= min.z() - margin && point.z() <= max.z() + margin;
    }

    private static List<BlockPoint> offsets() {
        List<BlockPoint> result = new ArrayList<>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -2; y <= 0; y++) result.add(new BlockPoint(x, y, z));
        result.sort(Comparator.comparingDouble(point -> point.distanceSq(new BlockPoint(0, -1, 0))));
        return List.copyOf(result);
    }

    private static final class Survey {
        final UUID world;
        final BlockPoint center;
        final List<Site> sites = new ArrayList<>();
        final Set<BlockPoint> added = new HashSet<>();
        int cursor;
        boolean unknown;
        long lastAdvancedAt = Long.MIN_VALUE;
        long finishedAt;

        Survey(AIPlayer ai) { world = ai.getWorldId(); center = ai.getPosition(); finishedAt = ai.getTicks(); }

        void advance(AIPlayer ai) {
            if (ai.getPlayer().getWorld().getEnvironment() != World.Environment.NORMAL) { cursor = OFFSETS.size(); return; }
            int remaining = PER_TICK;
            while (cursor < OFFSETS.size() && remaining-- > 0 && !ai.getWorkBudget().isExhausted()) {
                BlockPoint offset = OFFSETS.get(cursor++);
                BlockPoint cell = center.offset(offset.x(), offset.y(), offset.z());
                if (!neighborhoodLoaded(ai.getPlayer().getWorld(), cell)) { unknown = true; continue; }
                if (sourcePresent(ai, cell) || !validate(ai, cell, false).isEmpty() || stance(ai, cell) == null) continue;
                boolean wet = ai.getMemory().nearest(MemoryType.WATER_SOURCE, world, cell, ai.getTicks(), entry ->
                        Math.abs(entry.pos().x() - cell.x()) <= 4 && Math.abs(entry.pos().z() - cell.z()) <= 4
                                && entry.pos().y() >= cell.y() && entry.pos().y() <= cell.y() + 1
                                && sourcePresent(ai, entry.pos())).isPresent();
                if (wet || !added.add(cell)) continue;
                int benefit = 0;
                for (int[] side : SIDES) if (CropRules.tillable(Positions.block(ai.getPlayer().getWorld(), cell.offset(side[0], 0, side[1])).getType())) benefit++;
                sites.add(new Site(world, cell, !Positions.block(ai.getPlayer().getWorld(), cell).getType().isAir(), benefit,
                        Positions.block(ai.getPlayer().getWorld(), cell.offset(0, 1, 0)).getLightLevel()));
                if (sites.size() >= MAX_SITES) { cursor = OFFSETS.size(); break; }
            }
            if (cursor >= OFFSETS.size()) finishedAt = ai.getTicks();
        }
    }
}
