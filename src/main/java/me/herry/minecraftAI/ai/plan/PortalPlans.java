package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.BuildBlockAction;
import me.herry.minecraftAI.ai.action.CenterOnBlockAction;
import me.herry.minecraftAI.ai.action.EnterPortalAction;
import me.herry.minecraftAI.ai.action.IgniteAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.build.BlockRole;
import me.herry.minecraftAI.ai.build.Blueprint;
import me.herry.minecraftAI.ai.build.Blueprints;
import me.herry.minecraftAI.ai.build.BuildJob;
import me.herry.minecraftAI.ai.build.BuildMaterials;
import me.herry.minecraftAI.ai.build.SiteFinder;
import me.herry.minecraftAI.ai.memory.MemoryEntry;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import me.herry.minecraftAI.ai.world.WorldModel;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 네더 포탈의 틀을 짓고 불을 붙이는 계획. 집과 같은 방식으로, 매번 월드를 보고 아직 안 된 칸을 이어서 짓는다.
 * 집을 짓는 작업(AIPlayer 의 BuildJob)과는 따로 간다: 자리는 기억(PORTAL_SITE)에 적어 두고, 진행은 월드에서 읽는다.
 */
public final class PortalPlans {
    // 한 번의 계획으로 짓는 칸 수. 틀 전체가 한자리에서 손이 닿으므로 한 번에 다 짓는다.
    private static final int BATCH = 24;
    // 집 건물에서 이만큼은 떨어진 곳에 짓는다. 문 앞을 막지 않기 위해서다.
    private static final int HOME_GAP = 2;
    private static final double HOME_ARRIVE_RADIUS = 8.0;

    private PortalPlans() {
    }

    /**
     * 짓던 틀에 불이 붙어 포탈이 됐으면 포탈로 기억한다. 진행 단계를 판정하기 전에 불러야 바로 다음 단계로 넘어간다.
     */
    public static void checkCompletion(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BlockPoint origin = knownSite(ai, world);
        if (origin == null || !Positions.isLoaded(world, origin)) return;
        BlockPoint inside = insideOf(origin);
        if (Positions.block(world, inside).getType() != Material.NETHER_PORTAL) return;
        WorldModel model = ai.getWorldModel();
        if (model.nearestPortal(WorldModel.PortalKind.NETHER, world.getUID(), inside) != null) return;
        model.rememberPortal(WorldModel.PortalKind.NETHER, world.getUID(), inside);
        Base home = model.homeIn(world.getUID());
        if (home != null) home.setPortal(inside);
        ai.debug("Nether portal ready at " + inside);
        ai.getTeam().say(ai, Phrases.portalDone(), true);
    }

    /**
     * 틀에 이미 놓은 흑요석의 수. 놓은 만큼 가방에서 줄어들지만 "흑요석 10개"는 이룬 것으로 쳐야 한다.
     * 그러지 않으면 틀을 짓다 말고 흑요석을 더 캐러 간다.
     */
    public static int placedObsidian(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BlockPoint origin = knownSite(ai, world);
        if (origin == null || !Positions.isLoaded(world, origin)) return 0;
        int placed = 0;
        for (Blueprint.Part part : Blueprints.portal().partsOf(BlockRole.FRAME)) {
            if (Positions.block(world, part.at(origin)).getType() == Material.OBSIDIAN) placed++;
        }
        return placed;
    }

    static List<Action> buildPortal(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return List.of();
        BlockPoint origin = knownSite(ai, world);
        if (origin == null) {
            // 집 안에서는 지붕 때문에 하늘이 보이지 않는다. 땅속으로 착각해서 지붕을 뚫고 올라가려 하지 않도록 먼저 문밖으로 나간다.
            List<Action> leave = HomePlans.leaveBuilding(ai);
            if (!leave.isEmpty()) return leave;
            // 땅속에 있으면 먼저 지상으로 올라간다. 굴 안에서 자리를 고르지 않는다.
            if (TerrainPlans.needsToClimb(ai) || !TerrainPlans.isUnderOpenSky(world, ai.getPosition())) {
                List<Action> climb = TerrainPlans.climbOut(ai);
                if (!climb.isEmpty()) return climb;
            }
            // 포탈은 집 근처에 짓는다. 네더에서 돌아오면 바로 집이다.
            Base home = ai.getWorldModel().homeIn(world.getUID());
            if (home != null && !home.isInSafeRange(world.getUID(), ai.getPosition())) {
                return List.of(new MoveToAction(PathGoal.arrive(home.center(), HOME_ARRIVE_RADIUS), false));
            }
            origin = SiteFinder.find(world, ai.getPosition(), Blueprints.portal(), site -> !crowdsHome(home, world, site));
            if (origin == null) {
                ai.debug("No suitable place to build a nether portal around here");
                return List.of();
            }
            ai.getMemory().rememberPermanent(MemoryType.PORTAL_SITE, world.getUID(), origin, ai.getTicks());
            ai.debug("Building a nether portal at " + origin);
            ai.getTeam().say(ai, Phrases.portalSite(), true);
        }
        if (!Positions.isLoaded(world, origin)) return List.of(new MoveToAction(PathGoal.arrive(origin, 1.5), false));

        BuildJob job = new BuildJob(world.getUID(), origin, Blueprints.portal());
        Predicate<Blueprint.Part> done = doneCheck(world, origin);
        List<Action> actions = new ArrayList<>();
        if (!ai.getPosition().equals(origin)) actions.add(new MoveToAction(PathGoal.arrive(origin, 0.3), false));
        actions.add(new CenterOnBlockAction(origin));
        if (job.isComplete(done)) {
            // 틀이 다 됐다. 아랫줄 흑요석의 윗면에 불을 붙이면 틀 안이 포탈이 된다.
            actions.add(new IgniteAction(origin.offset(0, 0, -1)));
            return actions;
        }

        PlayerInventory inventory = ai.getPlayer().getInventory();
        int blocksLeft = BuildMaterials.structuralCount(inventory);
        int obsidianLeft = ai.getInventory().count(Material.OBSIDIAN);
        int steps = 0;
        for (Blueprint.Part part : job.pending(done)) {
            if (steps >= BATCH) break;
            BlockPoint target = part.at(origin);
            BlockRole role = part.role();
            Block cell = Positions.block(world, target);
            boolean obstructed = !cell.isEmpty() && !cell.isReplaceable();
            if (role == BlockRole.CLEAR) {
                actions.add(new BreakBlockAction(target));
            } else {
                // 재료가 떨어지면 여기까지만 짓는다. 틀은 아래에서 위로 이웃 블록에 붙여 가므로 건너뛰고 지을 수 없다.
                if (role == BlockRole.FRAME ? obsidianLeft-- <= 0 : blocksLeft-- <= 0) break;
                if (obstructed) actions.add(new BreakBlockAction(target));
                actions.add(new BuildBlockAction(target, role, BlockFace.SOUTH));
            }
            steps++;
        }
        return steps == 0 ? List.of() : actions;
    }

    // 지금 있는 차원에 아는 네더 포탈이 있는지. 포탈 칸 안에 서 있는 것(방금 도착함)도 아는 것으로 친다.
    public static boolean knowsPortalHere(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        return isPortal(world, ai.getPosition())
                || ai.getWorldModel().nearestPortal(WorldModel.PortalKind.NETHER, world.getUID(), ai.getPosition()) != null;
    }

    /**
     * 가장 가까운 포탈로 가서 들어간다. 오버월드에서는 네더로, 네더에서는 오버월드로 넘어간다.
     */
    static List<Action> usePortal(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BlockPoint feet = ai.getPosition();
        // 방금 도착해서 포탈 안에 서 있다. 한 번 나갔다가 다시 들어가는 것까지 그 행동이 한다.
        if (isPortal(world, feet)) return List.of(new EnterPortalAction(feet));
        WorldModel.Portal known = ai.getWorldModel().nearestPortal(WorldModel.PortalKind.NETHER, world.getUID(), feet);
        if (known == null) return List.of();
        BlockPoint portal = known.pos();
        if (!Positions.isLoaded(world, portal)) return List.of(new MoveToAction(PathGoal.arrive(portal, 3.0), false));
        BlockPoint front = isPortal(world, portal) ? frontOf(world, portal, feet) : null;
        if (front == null) {
            ai.debug("The portal at " + portal + " cannot be entered");
            return List.of();
        }
        List<Action> actions = new ArrayList<>();
        if (!feet.equals(front)) actions.add(new MoveToAction(PathGoal.arrive(front, 0.3), false));
        actions.add(new EnterPortalAction(portal));
        return actions;
    }

    // 포탈 칸 바로 앞의 설 자리. 포탈 칸은 틀의 아랫줄 위에 있어서 한 칸 낮은 자리에서 올라서기도 한다.
    private static @Nullable BlockPoint frontOf(World world, BlockPoint portal, BlockPoint from) {
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        BlockPoint best = null;
        for (int[] side : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int dy = 0; dy >= -1; dy--) {
                BlockPoint cell = portal.offset(side[0], dy, side[1]);
                boolean standable = terrain.classify(cell.x(), cell.y(), cell.z()) == BlockClass.OPEN
                        && terrain.classify(cell.x(), cell.y() + 1, cell.z()) == BlockClass.OPEN
                        && terrain.classify(cell.x(), cell.y() - 1, cell.z()) == BlockClass.SOLID;
                if (standable && (best == null || cell.distance(from) < best.distance(from))) best = cell;
            }
        }
        return best;
    }

    private static boolean isPortal(World world, BlockPoint cell) {
        return Positions.isLoaded(world, cell) && cell.y() >= world.getMinHeight() && cell.y() < world.getMaxHeight()
                && Positions.block(world, cell).getType() == Material.NETHER_PORTAL;
    }

    // 포탈 안쪽의 아래 칸. 불을 붙이는 칸이고, 포탈의 위치로 기억하는 칸이다.
    private static BlockPoint insideOf(BlockPoint origin) {
        return origin.offset(0, 1, -1);
    }

    private static @Nullable BlockPoint knownSite(AIPlayer ai, World world) {
        return ai.getMemory().nearest(MemoryType.PORTAL_SITE, world.getUID(), ai.getPosition(), ai.getTicks()).map(MemoryEntry::pos).orElse(null);
    }

    // 틀 안쪽은 걸어 들어갈 수 있으면 된다. 불이 붙어 포탈이 된 칸을 다시 비우려 하지 않는다.
    private static Predicate<Blueprint.Part> doneCheck(World world, BlockPoint origin) {
        return part -> {
            Material type = Positions.block(world, part.at(origin)).getType();
            if (part.role() == BlockRole.CLEAR && (type == Material.NETHER_PORTAL || type == Material.FIRE)) return true;
            return BuildMaterials.isDone(type, part.role());
        };
    }

    // 그 자리에 지으면 집 건물과 겹치거나 바짝 붙는지
    private static boolean crowdsHome(@Nullable Base home, World world, BlockPoint site) {
        if (home == null || !home.hasBuilding()) return false;
        for (Blueprint.Part part : Blueprints.portal().parts()) {
            BlockPoint point = part.at(site);
            for (int dx = -HOME_GAP; dx <= HOME_GAP; dx += HOME_GAP) {
                for (int dz = -HOME_GAP; dz <= HOME_GAP; dz += HOME_GAP) {
                    if (home.isInsideBuilding(world.getUID(), point.offset(dx, 0, dz))) return true;
                }
            }
        }
        return false;
    }
}
