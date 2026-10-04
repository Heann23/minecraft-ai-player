package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.BuildBlockAction;
import me.herry.minecraftAI.ai.action.CenterOnBlockAction;
import me.herry.minecraftAI.ai.action.CraftItemAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.build.BlockRole;
import me.herry.minecraftAI.ai.build.Blueprint;
import me.herry.minecraftAI.ai.build.Blueprints;
import me.herry.minecraftAI.ai.build.BuildJob;
import me.herry.minecraftAI.ai.build.BuildMaterials;
import me.herry.minecraftAI.ai.build.SiteFinder;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 집을 짓는 목표의 계획과, 지을 수 있는 상태인지(재료, 장소)의 판단.
 * 한 번의 계획은 몇 칸만 짓고 끝난다. 끝나면 그때의 월드를 다시 보고 다음 칸을 정하므로,
 * 중간에 재료를 구하러 다녀와도, 몬스터와 싸우고 와도 이어서 지을 수 있다.
 */
public final class BuildPlans {
    // 한 번의 계획으로 짓는 칸 수
    private static final int BATCH = 12;
    // 시설을 만드는 데 드는 판자 수
    private static final int DOOR_PLANKS = 6;
    private static final int CHEST_PLANKS = 8;
    private static final int TABLE_PLANKS = 4;
    // 지형에 따라 바닥을 메워야 할 수 있어서 넉넉히 잡는 여유분
    private static final int FLOOR_MARGIN = 4;
    private static final int BED_WOOL = 3;
    // 집을 지을 수 없는 차원에 있는 동안, 이 시간마다 다시 확인하면서 집 짓기를 미룬다.
    private static final long OTHER_WORLD_DEFER_TICKS = 600L;

    private BuildPlans() {
    }

    /**
     * 짓던 집이 다 지어졌으면 완성으로 처리한다. 진행 단계를 판정하기 전에 불러야,
     * 완성된 바로 그 판단에서 다음 단계(철 장비)로 넘어간다.
     */
    public static void checkCompletion(AIPlayer ai) {
        BuildJob job = ai.getBuildJob();
        if (job != null && isComplete(ai, ai.getPlayer().getWorld(), job)) finish(ai, job);
    }

    /**
     * 다음 중기 목표가 집 짓기일 때, 지금 지을 수 있는지와 부족한 재료를 상황 요약에 채운다.
     */
    public static void assess(AIPlayer ai, Situation situation) {
        World world = ai.getPlayer().getWorld();
        BuildJob job = ai.getBuildJob();
        if (job != null && !job.world().equals(world.getUID()) && Bukkit.getWorld(job.world()) == null) {
            // 짓던 집이 있던 월드가 없어졌다(월드 초기화 등). 그 작업은 버리고 여기서 새로 짓는다.
            abandon(ai);
            job = null;
        }
        boolean elsewhere = job != null && !job.world().equals(world.getUID());
        if (elsewhere || world.getEnvironment() != World.Environment.NORMAL) {
            // 집을 지을 수 없는 차원에 와 있다. 집 짓기에 막혀서 아무것도 못 하지 않도록, 그동안은 미뤄 두고 다음 일을 한다.
            ai.deferMilestone(Milestone.SHELTER, OTHER_WORLD_DEFER_TICKS);
            situation.shelterInProgress = job != null;
            situation.canBuildHere = false;
            return;
        }

        situation.shelterInProgress = job != null;
        // 땅속에 있어도 지을 수 있는 것으로 본다. 계획을 세울 때 먼저 지상으로 올라간다.
        situation.canBuildHere = !ai.getPlayer().isInWater();

        PlayerInventory inventory = ai.getPlayer().getInventory();
        int needed;
        int planks;
        if (job != null && isLoaded(world, job)) {
            Predicate<Blueprint.Part> done = doneCheck(world, job);
            needed = job.pendingStructural(done);
            planks = planksNeeded(inventory, job.pending(done));
        } else if (job != null) {
            // 멀리 떨어져 있어서 지금은 확인할 수 없다. 재료는 충분하다고 보고 일단 돌아가게 한다.
            needed = 0;
            planks = 0;
        } else {
            Blueprint blueprint = Blueprints.shelter();
            needed = blueprint.count(BlockRole.WALL) + blueprint.count(BlockRole.ROOF) + FLOOR_MARGIN;
            planks = planksNeeded(inventory, blueprint.parts());
        }
        situation.buildBlocksNeeded = needed;
        situation.buildBlocks = BuildMaterials.structuralCount(inventory);

        // 나무는 집을 짓는 지상에서 구하고 돌은 땅을 파서 구하므로, 둘 다 모자라면 나무부터 구한다.
        if (situation.plankEquivalent < planks) situation.need = Situation.Need.WOOD;
        else if (situation.buildBlocks < needed) situation.need = Situation.Need.STONE;
        else if (needed == 0 && job != null && isFixtureBlocked(ai, world, job, inventory)) situation.need = Situation.Need.WOOD;
        else situation.need = Situation.Need.NONE;
    }

    /**
     * 벽과 지붕은 다 올렸는데, 꼭 필요한 시설(문 등)을 가진 나무로는 만들 수 없는지.
     * 판자 수는 충분해도 나무 종류가 섞여 있으면 문을 만들 수 없다. 이때는 나무를 더 구해야 한다.
     */
    private static boolean isFixtureBlocked(AIPlayer ai, World world, BuildJob job, PlayerInventory inventory) {
        if (!job.world().equals(world.getUID()) || !isLoaded(world, job)) return false;
        for (Blueprint.Part part : job.pending(doneCheck(world, job))) {
            BlockRole role = part.role();
            if (role == BlockRole.CLEAR || role.isStructural() || !role.isRequired()) continue;
            return BuildMaterials.slotFor(inventory, role) < 0 && craftableFor(ai, role) == null;
        }
        return false;
    }

    /**
     * 다 지은 집에 아직 들이지 못한 시설(화로, 횃불, 침대)이 있고, 지금 그 아이템을 가지고 있는지.
     */
    public static boolean upgradeReady(AIPlayer ai, Base home) {
        if (!home.isSheltered() || ai.getBuildJob() != null) return false;
        PlayerInventory inventory = ai.getPlayer().getInventory();
        if (home.bed() == null && BuildMaterials.slotFor(inventory, BlockRole.BED) >= 0) return true;
        if (home.bed() == null && ai.getInventory().count(Tag.WOOL) >= BED_WOOL && craftableFor(ai, BlockRole.BED) != null) return true;
        return home.furnace() == null && BuildMaterials.slotFor(inventory, BlockRole.FURNACE) >= 0;
    }

    static List<Action> buildShelter(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BuildJob job = ai.getBuildJob();
        if (job == null) {
            Base home = ai.getWorldModel().homeIn(world.getUID());
            if (home != null && home.isSheltered()) {
                // 이미 지은 집이면 빠진 시설을 채우거나 부서진 곳을 고친다. 설계도와 기준점이 같으므로 같은 방식으로 이어서 지을 수 있다.
                job = new BuildJob(home.world(), home.center(), Blueprints.shelter());
            } else {
                // 돌을 캐느라 땅속에 있으면 먼저 지상으로 올라가서 자리를 찾는다. 얕은 굴이라도 머리 위가 막혀 있으면 아직 땅속이다.
                // 굴 안에서 자리를 고르면 구덩이 속에 집터를 잡게 된다.
                if (TerrainPlans.needsToClimb(ai) || !TerrainPlans.isUnderOpenSky(world, ai.getPosition())) {
                    List<Action> climb = TerrainPlans.climbOut(ai);
                    if (!climb.isEmpty()) return climb;
                }
                job = startJob(ai, world);
            }
            if (job == null) {
                ai.debug("No suitable place to build a shelter around here");
                return List.of();
            }
        }
        if (!job.world().equals(world.getUID()) || !isLoaded(world, job)) {
            // 짓던 집이 멀리 있으면 먼저 그쪽으로 간다.
            return job.world().equals(world.getUID()) ? List.of(new MoveToAction(PathGoal.arrive(job.origin(), 1.5), false)) : List.of();
        }

        BlockPoint origin = job.origin();
        List<Action> actions = new ArrayList<>();
        if (!ai.getPosition().equals(origin)) actions.add(new MoveToAction(PathGoal.arrive(origin, 0.3), false));
        // 한가운데에 서야 바로 옆 칸에 상자나 침대를 놓을 수 있다.
        actions.add(new CenterOnBlockAction(origin));

        PlayerInventory inventory = ai.getPlayer().getInventory();
        int blocksLeft = BuildMaterials.structuralCount(inventory);
        int steps = 0;
        for (Blueprint.Part part : job.pending(doneCheck(world, job))) {
            if (steps >= BATCH) break;
            BlockPoint target = part.at(origin);
            BlockRole role = part.role();
            // 꽃이나 묘목처럼 지나다닐 수는 있지만 그 위에 블록을 놓을 수는 없는 것이 있으면 먼저 치운다.
            Block cell = Positions.block(world, target);
            boolean obstructed = !cell.isEmpty() && !cell.isReplaceable();
            if (role == BlockRole.CLEAR) {
                actions.add(new BreakBlockAction(target, true));
            } else if (role.isStructural()) {
                // 블록이 떨어지면 여기까지만 짓는다. 남은 것은 재료를 구해 온 뒤에 이어서 짓는다.
                if (blocksLeft-- <= 0) break;
                if (obstructed) actions.add(new BreakBlockAction(target, true));
                actions.add(new BuildBlockAction(target, role, BlockFace.SOUTH));
            } else {
                int before = actions.size();
                if (obstructed) actions.add(new BreakBlockAction(target, true));
                if (!addFixture(ai, inventory, actions, part, target)) {
                    // 놓을 수 없는 시설 때문에 자리만 치워 두지는 않는다.
                    while (actions.size() > before) actions.removeLast();
                    if (role.isRequired()) break;
                    continue;
                }
            }
            steps++;
        }
        // 이동만 있고 지을 것이 없으면 계획이 없는 것이다.
        return steps == 0 ? List.of() : actions;
    }

    /**
     * 시설 하나를 놓는 행동을 더한다. 가진 것이 없으면 그 자리에서 만들어서 놓는다.
     *
     * @return 놓을 수 없으면(재료가 없으면) false
     */
    private static boolean addFixture(AIPlayer ai, PlayerInventory inventory, List<Action> actions, Blueprint.Part part, BlockPoint target) {
        BlockRole role = part.role();
        if (BuildMaterials.slotFor(inventory, role) < 0) {
            Material craftable = craftableFor(ai, role);
            if (craftable == null) return false;
            actions.add(new CraftItemAction(craftable, 1));
        }
        actions.add(new BuildBlockAction(target, role, facingOf(part)));
        return true;
    }

    // 그 역할의 시설 중 지금 가진 재료로 만들 수 있는 것. 문과 침대는 나무와 양털의 종류에 따라 여러 가지가 있다.
    private static @Nullable Material craftableFor(AIPlayer ai, BlockRole role) {
        Iterable<Material> candidates = switch (role) {
            case DOOR -> Tag.WOODEN_DOORS.getValues();
            case BED -> bedsMatchingWool(ai);
            case WORKBENCH -> List.of(Material.CRAFTING_TABLE);
            case CHEST -> List.of(Material.CHEST);
            case FURNACE -> List.of(Material.FURNACE);
            default -> List.<Material>of();
        };
        // 만들 수 있는 것 중 제작 단계가 가장 적은 것을 고른다. 흰 양털이 있는데 굳이 염료를 만들어 파란 침대를 만들지 않는다.
        Map<Material, Integer> stock = ai.getInventory().snapshot();
        Material best = null;
        int bestSteps = Integer.MAX_VALUE;
        for (Material candidate : candidates) {
            CraftingSystem.CraftPlan plan = ai.getCrafting().plan(candidate, 1, stock);
            if (plan.isFeasible() && plan.steps().size() < bestSteps) {
                best = candidate;
                bestSteps = plan.steps().size();
            }
        }
        return best;
    }

    // 가진 양털과 같은 색의 침대만 후보로 삼는다. 열여섯 가지 색을 모두 따져 볼 필요가 없고, 염료를 써서 색을 바꾸지도 않는다.
    private static List<Material> bedsMatchingWool(AIPlayer ai) {
        List<Material> beds = new ArrayList<>();
        for (Material item : ai.getInventory().snapshot().keySet()) {
            if (!Tag.WOOL.isTagged(item)) continue;
            Material bed = Material.getMaterial(item.name().replace("_WOOL", "_BED"));
            if (bed != null && Tag.BEDS.isTagged(bed)) beds.add(bed);
        }
        return beds;
    }

    // 문은 바깥쪽을, 상자와 화로는 실내 한가운데를 보게 놓는다. 침대는 발치에서 남쪽으로 머리를 둔다.
    private static BlockFace facingOf(Blueprint.Part part) {
        if (part.role() == BlockRole.DOOR || part.role() == BlockRole.BED) return BlockFace.SOUTH;
        if (Math.abs(part.dx()) >= Math.abs(part.dz())) return part.dx() > 0 ? BlockFace.WEST : BlockFace.EAST;
        return part.dz() > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    // 아직 놓지 않은 시설(문, 상자, 작업대) 중 가진 것이 없어서 만들어야 하는 것에 드는 판자 수
    private static int planksNeeded(PlayerInventory inventory, List<Blueprint.Part> pending) {
        int planks = 0;
        for (Blueprint.Part part : pending) {
            if (BuildMaterials.slotFor(inventory, part.role()) >= 0) continue;
            planks += switch (part.role()) {
                case DOOR -> DOOR_PLANKS;
                case CHEST -> CHEST_PLANKS;
                case WORKBENCH -> TABLE_PLANKS;
                default -> 0;
            };
        }
        return planks;
    }

    private static @Nullable BuildJob startJob(AIPlayer ai, World world) {
        Blueprint blueprint = Blueprints.shelter();
        BlockPoint site = SiteFinder.find(world, ai.getPosition(), blueprint);
        if (site == null) return null;

        BuildJob job = new BuildJob(world.getUID(), site, blueprint);
        ai.setBuildJob(job);
        // 처음 작업대를 놓았던 임시 거점을 집 자리로 옮긴다.
        Base home = ai.getWorldModel().homeIn(world.getUID());
        if (home == null) {
            home = new Base(world.getUID(), site);
            ai.getWorldModel().setHome(home);
        } else {
            home.setCenter(site);
        }
        home.setBounds(job.min(), job.max());
        ai.debug("Building a shelter at " + site);
        ai.getTeam().say(ai, Phrases.shelterSite(), true);
        return job;
    }

    /**
     * 짓던 집을 포기한다. 그 자리에서 계속 실패할 때 부른다. 다음에 다시 지을 때는 자리를 새로 고른다.
     */
    public static void abandon(AIPlayer ai) {
        if (ai.getBuildJob() == null) return;
        ai.debug("Giving up the shelter at " + ai.getBuildJob().origin());
        ai.setBuildJob(null);
        Base home = ai.getWorldModel().getHome();
        if (home != null && !home.isSheltered()) home.clearBounds();
    }

    private static void finish(AIPlayer ai, BuildJob job) {
        Base home = ai.getWorldModel().getHome();
        if (home == null || !home.world().equals(job.world())) {
            home = new Base(job.world(), job.origin());
            ai.getWorldModel().setHome(home);
        }
        home.setCenter(job.origin());
        home.setBounds(job.min(), job.max());
        home.setSheltered(true);
        ai.setBuildJob(null);
        ai.debug("Shelter finished at " + job.origin());
        ai.getTeam().say(ai, Phrases.shelterDone(), true);
    }

    private static boolean isComplete(AIPlayer ai, World world, BuildJob job) {
        if (!job.world().equals(world.getUID()) || !isLoaded(world, job)) return false;
        return job.isComplete(doneCheck(world, job));
    }

    private static Predicate<Blueprint.Part> doneCheck(World world, BuildJob job) {
        BlockPoint origin = job.origin();
        return part -> BuildMaterials.isDone(Positions.block(world, part.at(origin)).getType(), part.role());
    }

    // 건물 영역의 네 귀퉁이 청크가 모두 로드되어 있어야 블록을 읽을 수 있다.
    private static boolean isLoaded(World world, BuildJob job) {
        BlockPoint min = job.min();
        BlockPoint max = job.max();
        return world.isChunkLoaded(min.x() >> 4, min.z() >> 4) && world.isChunkLoaded(max.x() >> 4, min.z() >> 4)
                && world.isChunkLoaded(min.x() >> 4, max.z() >> 4) && world.isChunkLoaded(max.x() >> 4, max.z() >> 4);
    }
}
