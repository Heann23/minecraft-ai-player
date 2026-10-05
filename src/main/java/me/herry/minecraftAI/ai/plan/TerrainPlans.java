package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.EscapeHazardAction;
import me.herry.minecraftAI.ai.action.ExploreAreaAction;
import me.herry.minecraftAI.ai.action.MarkShaftAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PillarUpAction;
import me.herry.minecraftAI.ai.action.PlaceFillerAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.navigation.AStarSearch;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.Enclosure;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.navigation.ShaftAccess;
import me.herry.minecraftAI.ai.navigation.WaterExitSearch;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.team.ShaftRegistry;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 땅을 파거나 블록을 놓아서 길을 만드는 계획: 계단, 수평 굴, 다리, 블록 쌓아 올라가기, 이미 파 둔 굴 따라가기.
 * 모두 한 번에 한 칸씩만 진행하고, 행동이 끝나면 다시 계획을 세워서 그때의 지형에 맞춰 다음 칸을 정한다.
 */
public final class TerrainPlans {
    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    // 월드 바닥(기반암) 근처까지는 파 내려가지 않는다.
    private static final int MIN_DIG_MARGIN = 8;
    // 계단 한 단(1칸)에 더해서 추가로 떨어져도 되는 높이. 합쳐서 낙하 피해가 없는 3칸을 넘지 않는다.
    private static final int MAX_EXTRA_DROP = 2;
    private static final int CAVE_DEPTH = 6;
    // 주변 지표면이 대부분 이만큼 높으면 협곡이나 깊은 구덩이 바닥에 있는 것으로 본다.
    private static final int PIT_DEPTH = 8;
    private static final int PIT_RADIUS = 8;
    private static final int PIT_SAMPLES = 5;
    // 갇혔을 때 주변 지면이 발보다 한 칸이라도 높으면 위로, 아니면 아래로 빠져나간다.
    private static final int LOW_RADIUS = 3;
    private static final int LOW_DIFF = 1;
    // 계단을 팔 벽을 찾는 거리
    private static final int WALL_SEARCH = 12;
    // 몬스터가 이 거리 안에 있는 다른 빈 곳으로는 굴을 뚫지 않는다.
    private static final int HIDDEN_WAIT_TICKS = 100;

    private TerrainPlans() {
    }

    // 머리 위와 주변 대부분이 땅으로 덮여 있는지. 산의 국소적인 바위 지붕과 나뭇잎은 지하로 보지 않는다.
    public static boolean isDeepUnderground(World world, BlockPoint feet) {
        boolean ceiling = hasCeiling(world);
        int surface = surfaceY(world, feet.x(), feet.z());
        if (!SurfaceRules.isDeepUnderground(ceiling, surface, feet.y(), CAVE_DEPTH)) return false;
        // 읽지 못하는 청크를 지상의 출구로 가정하지 않는다. 높이맵 8곳만 읽고 청크를 새로 불러오지 않는다.
        return SurfaceRules.isDeepUnderground(ceiling, surface, feet.y(), CAVE_DEPTH,
                countHigherSamples(world, feet, PIT_RADIUS, CAVE_DEPTH, true));
    }

    // 머리 위로 하늘이 열려 있는지 (나뭇잎은 가린 것으로 치지 않는다). 굴이나 동굴 안이면 false.
    public static boolean isUnderOpenSky(World world, BlockPoint feet) {
        return SurfaceRules.isUnderOpenSky(hasCeiling(world), surfaceY(world, feet.x(), feet.z()), feet.y());
    }

    // 협곡이나 깊은 구덩이 바닥처럼 주변 지면이 대부분 훨씬 높은 곳인지.
    public static boolean isInPit(World world, BlockPoint feet) {
        return countHigherSamples(world, feet, PIT_RADIUS, PIT_DEPTH) >= PIT_SAMPLES;
    }

    /**
     * 사냥, 나무, 탐험처럼 지상에서 할 일을 하려면 먼저 땅 위로 올라가야 하는지.
     * 동굴에 떨어졌거나 깊이 파 내려간 굴 안에서는 걸어서 지상의 목적지까지 갈 길을 찾지 못한다.
     */
    public static boolean needsToClimb(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BlockPoint feet = ai.getPosition();
        // 좁은 굴의 바깥은 낮은 지면일 수 있다. 실제로 기록한 굴 안이고 입구가 위에 있으면 그 길로 올라간다.
        boolean inRecordedShaft = !isUnderOpenSky(world, feet)
                && ai.getTeam().getShafts().higher(world.getUID(), feet, 2.0, 4) != null;
        return inRecordedShaft || isDeepUnderground(world, feet) || isInPit(world, feet);
    }

    // 주변(반경 3칸)의 지면이 발보다 높은 곳이 많은지. 갇혔을 때 위로 나갈지 아래로 나갈지 정한다.
    static boolean isLowerThanSurroundings(World world, BlockPoint feet) {
        return countHigherSamples(world, feet, LOW_RADIUS, LOW_DIFF) >= PIT_SAMPLES;
    }

    private static int countHigherSamples(World world, BlockPoint feet, int radius, int minDiff) {
        return countHigherSamples(world, feet, radius, minDiff, false);
    }

    private static int countHigherSamples(World world, BlockPoint feet, int radius, int minDiff, boolean unknownIsHigher) {
        int higher = 0;
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * i / 4.0;
            int x = feet.x() + (int) Math.round(Math.cos(angle) * radius);
            int z = feet.z() + (int) Math.round(Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                if (unknownIsHigher) higher++;
                continue;
            }
            if (SurfaceRules.isHigherGround(hasCeiling(world), surfaceY(world, x, z), feet.y(), minDiff)) higher++;
        }
        return higher;
    }

    // 네더는 천장(기반암)이 가장 높은 블록이라 "지표면"이 없다.
    private static boolean hasCeiling(World world) {
        return world.getEnvironment() == World.Environment.NETHER;
    }

    // 그 위치의 지면에 섰을 때 발이 놓이는 높이. 가장 높은 블록의 바로 위 칸이다.
    private static int surfaceY(World world, int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
    }

    /**
     * 지상으로 한 단계 올라간다. 지나온 굴이 있으면 그 굴로 걸어 올라가고, 없으면 계단을 파고,
     * 계단을 팔 벽도 없으면(굴이 너무 많이 파여 있거나 넓은 동굴) 블록을 쌓아 올라간다. 방법이 없으면 빈 목록.
     */
    public static List<Action> climbOut(AIPlayer ai) {
        List<Action> waterExit = leaveWater(ai);
        if (!waterExit.isEmpty()) return waterExit;
        List<Action> shaft = ShaftPlans.followShaftUp(ai);
        if (!shaft.isEmpty()) return shaft;
        if (!ai.getBody().isGrounded()) return List.of();

        List<Action> stair = digStairUp(ai, directionOrder(ai));
        if (!stair.isEmpty()) return stair;
        // 숨어 있는 자리의 바로 위나 옆이 몬스터가 있는 곳이라 뚫을 수 없으면, 옆으로 굴을 파서 자리를 옮긴 다음에 올라간다.
        boolean hidden = RefugePlans.isHidden(ai);
        // 머리 위가 지나온 굴의 발판이라 캘 수 없을 때도 마찬가지다. 계단은 어느 쪽으로 내든 머리 위를 캐야 하므로 옆으로 한 칸 비켜난다.
        World world = ai.getPlayer().getWorld();
        boolean underShaft = cutsShaft(ai, world, new BukkitTerrainView(world), ai.getPosition().offset(0, 2, 0));
        List<Action> aside = hidden || underShaft ? digTunnel(ai, false) : List.<Action>of();
        // 캘 것 없이 숨은 자리 안에서 걷기만 하는 것은 자리를 옮기는 것이 아니다.
        if (aside.stream().anyMatch(BreakBlockAction.class::isInstance)) return aside;

        List<Action> pillar = pillarUp(ai, null);
        if (!pillar.isEmpty()) {
            ai.getTeam().say(ai, Phrases.climbingOut(), false);
            return pillar;
        }
        // 숨은 자리의 사방이 몬스터 쪽이라 어디로도 뚫을 수 없으면 그 안에서 기다린다. 걸어서 옮겨 갈 곳이 없다.
        if (hidden) return List.of(new WaitAction(HIDDEN_WAIT_TICKS));
        // 동굴 한가운데라 계단을 낼 벽이 바로 옆에 없고 쌓을 블록도 없으면, 가장 가까운 벽 앞으로 가서 그 벽에 계단을 판다.
        List<Action> wall = moveToWall(ai);
        if (!wall.isEmpty()) return wall;
        if (isDeepUnderground(ai.getPlayer().getWorld(), ai.getPosition())) {
            ai.debug("No way up from here, moving elsewhere in the cave");
            return List.of(ExploreAreaAction.cave(4.0, 12.0));
        }
        return List.of();
    }

    // 사방 중 벽(꽉 찬 블록)이 가장 가까운 쪽으로, 그 벽 바로 앞 칸까지 걸어간다. 그 쪽을 파는 방향으로 정해 둔다.
    private static List<Action> moveToWall(AIPlayer ai) {
        BukkitTerrainView terrain = new BukkitTerrainView(ai.getPlayer().getWorld());
        BlockPoint feet = ai.getPosition();
        BlockFace bestFace = null;
        int bestDistance = Integer.MAX_VALUE;
        for (BlockFace face : HORIZONTAL) {
            for (int distance = 1; distance <= WALL_SEARCH && distance < bestDistance; distance++) {
                BlockPoint cell = feet.offset(face.getModX() * distance, 0, face.getModZ() * distance);
                if (terrain.classify(cell.x(), cell.y(), cell.z()) == BlockClass.SOLID) {
                    // 바로 옆이 벽인데도 계단을 못 냈다면 그 벽은 팔 수 없는 벽이다(물, 용암 등).
                    if (distance > 1) {
                        bestFace = face;
                        bestDistance = distance;
                    }
                    break;
                }
                if (!AStarSearch.isStandable(terrain, cell.x(), cell.y(), cell.z())) break;
            }
        }
        if (bestFace == null) return List.of();
        ai.setDigDirection(bestFace);
        BlockPoint stand = feet.offset(bestFace.getModX() * (bestDistance - 1), 0, bestFace.getModZ() * (bestDistance - 1));
        ai.debug("Walking to the wall at " + stand + " to dig stairs up");
        return List.of(new MoveToAction(PathGoal.arrive(stand, 0.3), false));
    }

    // 물속에 있으면 가까운 마른 땅으로 나오는 계획. 물속이 아니거나 가까이에 마른 땅이 없으면 빈 목록.
    public static List<Action> leaveWater(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.isInWater()) return List.of();
        World world = player.getWorld();
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        var home = ai.getWorldModel().homeIn(world.getUID());
        WaterExitSearch.Exit exit = WaterExitSearch.find(terrain, ai.getPosition(), block ->
                Positions.block(world, block).getType().getHardness() >= 0.0F
                        && (home == null || !home.isInsideBuilding(world.getUID(), block))
                        && !cutsShaft(ai, world, terrain, block));
        if (exit == null) return List.of();
        List<Action> actions = new ArrayList<>();
        if (!exit.blocksToBreak().isEmpty()) ai.debug("Opening a water exit at " + exit.feet() + " by clearing " + exit.blocksToBreak());
        for (BlockPoint block : exit.blocksToBreak()) actions.add(new BreakBlockAction(block));
        actions.add(EscapeHazardAction.leaveWater(exit.feet()));
        return actions;
    }

    /**
     * 좁은 굴 안이라 작업대나 화로를 놓을 자리가 없을 때, 옆 벽을 한 칸 파서 자리를 만들 칸을 고른다.
     * 바닥이 있고 물이나 용암에 닿지 않는 벽이어야 한다. 그런 벽이 없으면 null.
     */
    public static @Nullable BlockPoint nookToDig(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        BlockPoint feet = ai.getPosition();
        for (BlockFace face : HORIZONTAL) {
            BlockPoint cell = feet.offset(face.getModX(), 0, face.getModZ());
            if (terrain.classify(cell.x(), cell.y(), cell.z()) != BlockClass.SOLID) continue;
            if (terrain.classify(cell.x(), cell.y() - 1, cell.z()) != BlockClass.SOLID) continue;
            // 계단 굴 안에서는 바로 뒤의 벽이 윗단의 발판이다. 그런 벽은 파지 않는다.
            if (isDigSafe(world, terrain, cell) && !cutsShaft(ai, world, terrain, cell)) return cell;
        }
        return null;
    }

    // 블록을 하나 쌓고 그 위에 올라선다. 쌓을 블록이 없거나 머리 위가 위험하면 빈 목록.
    public static List<Action> pillarUp(AIPlayer ai, @Nullable Consumer<BlockPoint> onPlaced) {
        if (!PillarUpAction.canPillar(ai)) return List.of();
        World world = ai.getPlayer().getWorld();
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        BlockPoint ceiling = ai.getPosition().offset(0, 2, 0);
        // 올라서려면 머리 위 칸을 캐야 한다. 그 칸이 몬스터가 있는 쪽으로 뚫리면 숨은 자리가 열린다.
        if (RefugePlans.breaksCover(ai, terrain, ceiling)) return List.of();
        // 그 칸이 지나온 굴의 발판이면 캐는 행동이 거절한다. 그래도 계획을 세우면 그 자리에서 1초에 몇 번씩 실패만 되풀이한다
        // (기본 시드의 y 54 에서 10분 동안 그랬다). 다른 방법(벽으로 가서 계단 파기, 자리 옮기기)으로 넘어가게 한다.
        if (cutsShaft(ai, world, terrain, ceiling)) return List.of();
        ai.debug("Placing a block underfoot to climb up from " + ai.getPosition());
        return List.of(new PillarUpAction(onPlaced));
    }

    /**
     * 걸어갈 길이 없는 대상(협곡 건너편이나 벽 너머의 광석 등)을 향해 한 칸 나아간다.
     * 아래에 있으면 계단을 파 내려가고, 위에 있으면 계단을 파 올라가거나 블록을 쌓고,
     * 같은 높이면 앞이 비어 있을 때 다리를 놓고 막혀 있을 때 굴을 판다.
     */
    public static List<Action> stepToward(AIPlayer ai, BlockPoint target, boolean record) {
        BlockPoint feet = ai.getPosition();
        int dx = target.x() - feet.x();
        int dz = target.z() - feet.z();
        BlockFace toward = null;
        if (dx != 0 || dz != 0) {
            toward = Math.abs(dx) >= Math.abs(dz)
                    ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST)
                    : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
            ai.setDigDirection(toward);
        }
        // 곧장 가는 쪽을 팔 수 없으면(지나온 계단의 바로 밑 등) 옆으로 한 단 비켜서 판다. 다음 계획에서 옆에서 다가간다.
        List<BlockFace> directions = toward == null ? directionOrder(ai) : DigRules.sidesToward(toward, dx, dz);
        int dy = target.y() - feet.y();
        int horizontal = Math.max(Math.abs(dx), Math.abs(dz));

        List<Action> step = List.of();
        if (dy < -1) {
            step = digStairDown(ai, false, directions, record);
        } else if (dy > 1) {
            // 바로 위에 있으면 계단을 낼 수 없으니 블록을 쌓아 올라간다.
            if (horizontal > 2) step = digStairUp(ai, directions);
            if (step.isEmpty()) step = pillarUp(ai, null);
        }
        if (step.isEmpty() && toward != null) step = bridgeStep(ai, toward);
        if (step.isEmpty() && toward != null) step = digTunnel(ai, directions, record);
        return step;
    }

    /**
     * 발 앞이 낭떠러지(협곡, 구덩이)일 때 그 빈칸 아래에 블록을 놓고 한 칸 나아간다.
     * 앞에 바닥이 있거나 블록이 없으면 빈 목록.
     */
    public static List<Action> bridgeStep(AIPlayer ai, BlockFace direction) {
        if (!ai.getBody().isGrounded() || ai.getInventory().findFillerSlot() < 0) return List.of();
        BukkitTerrainView terrain = new BukkitTerrainView(ai.getPlayer().getWorld());
        BlockPoint feet = ai.getPosition();
        BlockPoint front = feet.offset(direction.getModX(), 0, direction.getModZ());
        BlockPoint floor = front.offset(0, -1, 0);
        BlockClass floorClass = terrain.classify(floor.x(), floor.y(), floor.z());
        if (floorClass != BlockClass.OPEN && floorClass != BlockClass.WATER) return List.of();
        if (terrain.classify(front.x(), front.y(), front.z()) != BlockClass.OPEN
                || terrain.classify(front.x(), front.y() + 1, front.z()) != BlockClass.OPEN) return List.of();

        ai.debug("Bridging " + direction + " over " + floor);
        ai.getTeam().say(ai, Phrases.bridging(), false);
        return List.of(new PlaceFillerAction(floor), new MoveToAction(PathGoal.arrive(front, 0.3), false));
    }

    /**
     * 한 칸 앞, 한 칸 아래로 내려가는 계단 한 단을 판다.
     * 발밑을 수직으로 파면 다시 올라올 수 없지만, 계단은 걸어서 오르내릴 수 있다.
     *
     * @param allowDrop 계단 끝에서 몇 칸 뛰어내리는 것을 허용할지. 뛰어내린 곳은 다시 올라올 수 없으므로
     *                  높은 곳에서 내려올 때만 쓰고, 돌을 캐러 내려갈 때는 쓰지 않는다.
     * @param record    판 계단을 동료와 함께 쓰는 굴로 기록할지
     */
    static List<Action> digStairDown(AIPlayer ai, boolean allowDrop, List<BlockFace> directions, boolean record) {
        if (!ai.getBody().isGrounded()) return List.of();
        World world = ai.getPlayer().getWorld();
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        BlockPoint feet = ai.getPosition();
        if (feet.y() - 2 < world.getMinHeight() + MIN_DIG_MARGIN) return List.of();

        for (BlockFace direction : directions) {
            BlockPoint front = feet.offset(direction.getModX(), 0, direction.getModZ());
            BlockPoint head = front.offset(0, 1, 0);
            BlockPoint step = front.offset(0, -1, 0);
            BlockPoint landing = findLanding(terrain, step, allowDrop ? MAX_EXTRA_DROP : 0);
            if (landing == null) continue;

            List<Action> actions = digThrough(ai, world, terrain, landing, record, head, front, step);
            if (actions.isEmpty()) continue;
            ai.setDigDirection(direction);
            ai.debug("Digging stair step " + direction + " down to " + landing);
            return actions;
        }
        return List.of();
    }

    // 파던 방향부터 차례로 시도한다.
    static List<Action> digStairDown(AIPlayer ai, boolean allowDrop, boolean record) {
        return digStairDown(ai, allowDrop, directionOrder(ai), record);
    }

    /**
     * 한 칸 앞, 한 칸 위로 올라가는 계단 한 단을 판다. 땅속에서 지상으로 나올 때 쓴다.
     */
    static List<Action> digStairUp(AIPlayer ai, List<BlockFace> directions) {
        if (!ai.getBody().isGrounded()) return List.of();
        World world = ai.getPlayer().getWorld();
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        BlockPoint feet = ai.getPosition();
        if (feet.y() + 3 >= world.getMaxHeight()) return List.of();

        for (BlockFace direction : directions) {
            BlockPoint front = feet.offset(direction.getModX(), 0, direction.getModZ());
            // 앞 칸이 비어 있으면 딛고 올라설 단이 없다.
            if (terrain.classify(front.x(), front.y(), front.z()) != BlockClass.SOLID) continue;

            BlockPoint ceiling = feet.offset(0, 2, 0);
            BlockPoint newFeet = front.offset(0, 1, 0);
            BlockPoint newHead = front.offset(0, 2, 0);
            List<Action> actions = digThrough(ai, world, terrain, newFeet, false, newFeet, newHead, ceiling);
            if (actions.isEmpty()) continue;
            ai.setDigDirection(direction);
            ai.debug("Digging stair step " + direction + " up to " + newFeet);
            return actions;
        }
        return List.of();
    }

    /**
     * 같은 높이로 한 칸 앞으로 나아가는 굴을 판다 (높이 2칸). 굴 벽에 드러나는 광석은 블록 검색이 찾아낸다.
     */
    static List<Action> digTunnel(AIPlayer ai, List<BlockFace> directions, boolean record) {
        if (!ai.getBody().isGrounded()) return List.of();
        World world = ai.getPlayer().getWorld();
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        BlockPoint feet = ai.getPosition();

        for (BlockFace direction : directions) {
            BlockPoint front = feet.offset(direction.getModX(), 0, direction.getModZ());
            // 바닥이 없는 곳(동굴의 낭떠러지)으로는 굴을 내지 않는다. 그런 곳은 다리를 놓는다.
            if (terrain.classify(front.x(), front.y() - 1, front.z()) != BlockClass.SOLID) continue;

            List<Action> actions = digThrough(ai, world, terrain, front, record, front.offset(0, 1, 0), front);
            if (actions.isEmpty()) continue;
            ai.setDigDirection(direction);
            ai.debug("Digging tunnel " + direction + " to " + front);
            return actions;
        }
        return List.of();
    }

    static List<Action> digTunnel(AIPlayer ai, boolean record) {
        return digTunnel(ai, directionOrder(ai), record);
    }

    // 지나갈 칸들을 파내고 destination 으로 이동하는 행동 목록. 안전하게 팔 수 없으면 빈 목록.
    private static List<Action> digThrough(AIPlayer ai, World world, BukkitTerrainView terrain, BlockPoint destination,
                                           boolean record, BlockPoint... blocks) {
        if (!isDigSafe(world, terrain, blocks) || undermines(ai, world, terrain, blocks) || RefugePlans.breaksCover(ai, terrain, blocks)) return List.of();

        List<Action> actions = new ArrayList<>();
        for (BlockPoint block : blocks) {
            if (terrain.classify(block.x(), block.y(), block.z()) != BlockClass.OPEN) actions.add(new BreakBlockAction(block));
        }
        // 캘 것 없이 막다른 굴을 따라 걷기만 하는 것은 파는 것이 아니다. 다른 방향을 시도하게 한다.
        if (actions.isEmpty() && ai.getTeam().getShafts().isDeadEndStep(world.getUID(), destination)) return List.of();
        actions.add(new MoveToAction(PathGoal.arrive(destination, 0.5), false));
        if (record) {
            // 굴의 첫 칸이면 지금 서 있는 자리(입구)부터 기록한다.
            actions.add(new MarkShaftAction(ai.getPosition()));
            actions.add(new MarkShaftAction(destination));
        }
        return actions;
    }

    // 파낼 블록 가운데 지나온 굴의 발판이 있는지. 계단 바로 밑으로 굴을 내면 발판이 없어져서 다시 올라갈 수 없다.
    static boolean cutsShaft(AIPlayer ai, World world, BukkitTerrainView terrain, BlockPoint... blocks) {
        ShaftRegistry shafts = ai.getTeam().getShafts();
        for (BlockPoint block : blocks) {
            if (terrain.classify(block.x(), block.y(), block.z()) != BlockClass.SOLID) continue;
            if (DigRules.cutsShaft(block, point -> shafts.isStep(world.getUID(), point))) return true;
        }
        return false;
    }

    /**
     * 파면 안 되는 블록이 섞여 있는지: 지나온 굴의 발판, 굴의 끝에서 여기까지 걸어온 길의 바닥, 집의 일부, 굽는 중인 화로.
     * 굴 끝에서 몇 칸 걸어온 자리에서 다시 파면 그 사이의 칸은 아직 굴로 기록되지 않았지만, 그 바닥을 파내면 굴과의 사이가 끊긴다.
     * (걸어 다닌 칸을 모두 보호하면 지상에서는 어느 쪽으로도 계단을 낼 수 없게 되므로, 굴과 이어지는 길만 본다.)
     * 집의 블록은 캐는 행동이 어차피 거부하므로, 여기서 걸러서 다른 방향을 시도하게 한다.
     */
    private static boolean undermines(AIPlayer ai, World world, BukkitTerrainView terrain, BlockPoint... blocks) {
        if (cutsShaft(ai, world, terrain, blocks)) return true;
        ShaftRegistry.Shaft latest = ai.getTeam().getShafts().latestOf(ai.getName(), world.getUID());
        List<BlockPoint> link = ShaftAccess.link(terrain, latest == null ? null : latest.end(), ai.getPosition());
        Base home = ai.getWorldModel().homeIn(world.getUID());
        for (BlockPoint block : blocks) {
            if (terrain.classify(block.x(), block.y(), block.z()) != BlockClass.SOLID) continue;
            if (DigRules.cutsShaft(block, link::contains)) return true;
            if (home != null && home.isInsideBuilding(world.getUID(), block)) return true;
            // 넣어 둔 것이 있는 화로를 굴 길에서 파내면 굽던 것이 쏟아진다. 그 칸은 비켜서 판다.
            if (FurnacePlans.holdsJob(ai, world.getUID(), block)) return true;
        }
        return false;
    }

    // 파던 방향을 먼저 시도해서 계단이 한 방향으로 이어지게 한다.
    static List<BlockFace> directionOrder(AIPlayer ai) {
        List<BlockFace> order = new ArrayList<>();
        BlockFace preferred = ai.getDigDirection();
        if (preferred == null) preferred = facingOf(ai.getPlayer().getLocation().getYaw());
        order.add(preferred);
        // 왔던 길로 되돌아가는 방향은 맨 마지막에 시도한다.
        BlockFace opposite = preferred.getOppositeFace();
        for (BlockFace face : HORIZONTAL) {
            if (face != preferred && face != opposite) order.add(face);
        }
        order.add(opposite);
        return order;
    }

    private static BlockFace facingOf(float yaw) {
        int index = Math.floorMod(Math.round(yaw / 90.0F), 4);
        return switch (index) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    // 계단을 파고 내려섰을 때 발이 닿을 칸. 바로 아래가 비어 있어도 다치지 않을 높이 안에 바닥이 있으면 된다.
    private static @Nullable BlockPoint findLanding(BukkitTerrainView terrain, BlockPoint step, int maxExtraDrop) {
        for (int drop = 0; drop <= maxExtraDrop; drop++) {
            int y = step.y() - drop;
            BlockClass below = terrain.classify(step.x(), y - 1, step.z());
            if (below == BlockClass.SOLID) return new BlockPoint(step.x(), y, step.z());
            if (below != BlockClass.OPEN) return null;
        }
        return null;
    }

    // 파낼 칸들이 캘 수 있는 블록이고, 그 주변에 용암이나 물이 없는지 확인한다.
    private static boolean isDigSafe(World world, BukkitTerrainView terrain, BlockPoint... blocks) {
        for (BlockPoint block : blocks) {
            BlockClass blockClass = terrain.classify(block.x(), block.y(), block.z());
            if (blockClass != BlockClass.OPEN && blockClass != BlockClass.SOLID) return false;
            if (blockClass == BlockClass.SOLID && Positions.block(world, block).getType().getHardness() < 0.0F) return false;
            if (touchesLiquid(world, terrain, block)) return false;
        }
        return true;
    }

    private static boolean touchesLiquid(World world, BukkitTerrainView terrain, BlockPoint block) {
        for (int[] neighbor : NEIGHBORS) {
            int x = block.x() + neighbor[0];
            int y = block.y() + neighbor[1];
            int z = block.z() + neighbor[2];
            if (terrain.classify(x, y, z) == BlockClass.WATER) return true;
            // classify() 가 UNLOADED 가 아닌 값을 돌려줬다면 청크가 로드되어 있으므로 블록을 읽어도 된다.
            if (terrain.classify(x, y, z) == BlockClass.DANGER && typeAt(world, x, y, z) == Material.LAVA) return true;
        }
        return false;
    }

    private static @Nullable Material typeAt(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return null;
        return world.getBlockAt(x, y, z).getType();
    }
}
