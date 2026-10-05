package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.ExploreAreaAction;
import me.herry.minecraftAI.ai.action.LootChestAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.action.PlaceBlockAction;
import me.herry.minecraftAI.ai.action.PlugWaterAction;
import me.herry.minecraftAI.ai.action.RememberPlaceAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.perception.TreeSpotter;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.team.ShaftRegistry;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 나무, 돌, 광물을 모으고 주변을 탐험하는 목표의 행동 계획.
 */
public final class GatherPlans {
    // 눈높이에서 블록까지 이 거리 안으로 다가가면 캘 수 있다.
    private static final double BREAK_REACH = 4.0;
    private static final double DROP_RADIUS = 6.0;
    // 목표를 고를 때(SituationBuilder)도 같은 범위로 아이템을 찾는다.
    public static final double LOOSE_DROP_RADIUS = 8.0;
    // 이 거리 안에 드러난 돌이 있으면 걸어가서 캐고, 없으면 그 자리에서 파 내려간다.
    private static final double STONE_WALK_RANGE = 24.0;
    private static final long STONE_RETRY_WINDOW = 200L;
    public static final double ORE_WALK_RANGE = 32.0;
    // 걸어갈 길은 없지만 이 거리 안에 있는 광석은 굴을 파거나 다리를 놓아서 다가간다.
    private static final double DIG_TOWARD_RANGE = 20.0;
    // 이 거리 안에 보이는 광석은 광맥이 이어지는 것으로 보고 다 캔다.
    private static final double VEIN_RANGE = 6.0;
    // 철이 가장 많이 나오는 높이 부근. 이 높이까지 계단으로 내려간 뒤 수평으로 굴을 판다.
    private static final int IRON_LEVEL = 24;
    private static final int IRON_LEVEL_SLACK = 8;
    private static final long CAVE_RETRY_WINDOW = 200L;
    private static final int NIGHT_WAIT_TICKS = 100;
    // 파 놓은 굴의 끝에 이만큼 가까이 있으면 동굴을 돌아다니지 않고 그 굴을 이어서 판다.
    private static final double TUNNEL_FACE_RANGE = 2.0;
    private static final double CHEST_REACH = 3.5;
    private static final int WATER_SEARCH_RADIUS = 5;
    private static final int MAX_SOURCES_TO_PLUG = 8;
    // 다이아몬드는 월드 바닥에서 이만큼 위(기본 월드에서 y -53)에 발을 두고 굴을 팔 때 가장 잘 나온다.
    // 이보다 아래는 동굴의 빈 곳이 용암으로 차 있다.
    private static final int DIAMOND_ABOVE_BOTTOM = 11;
    private static final int DIAMOND_LEVEL_SLACK = 6;
    // 멀리 보이는 나뭇잎 쪽으로 갈 때 이 거리 안까지 다가간다. 가 본 곳은 5분 동안 다시 고르지 않는다.
    private static final double CANOPY_ARRIVE_RADIUS = 8.0;
    private static final long CANOPY_CHECK_TTL = 6000L;

    private GatherPlans() {
    }

    // 나무를 찾아 지상을 돌아다닌다. 동굴에 떨어졌거나 깊은 굴 안이면 지상까지 걸어갈 길이 없으므로 먼저 올라간다.
    static List<Action> findWood(AIPlayer ai) {
        if (TerrainPlans.needsToClimb(ai)) {
            List<Action> climb = TerrainPlans.climbOut(ai);
            if (!climb.isEmpty()) return climb;
        }
        // 멀리 나뭇잎이 보이면 아무 데나 돌아다니지 않고 그쪽으로 간다. 가까이 가면 주변 블록 검색이 원목을 찾아낸다.
        BlockPoint canopy = TreeSpotter.spot(ai);
        if (canopy != null) {
            ai.debug("Spotted leaves in the distance, heading to " + canopy);
            // 도착하고도 원목이 없으면(덤불 등) 같은 곳을 다시 고르지 않도록 적어 둔다. 길이 없으면 "갈 수 없는 곳"으로 남는다.
            // 가는 도중에 다른 일로 끊겼을 때는 적지 않으므로, 나중에 다시 그 나무로 간다.
            return List.of(new MoveToAction(PathGoal.arrive(canopy, CANOPY_ARRIVE_RADIUS), true),
                    new RememberPlaceAction(MemoryType.CHECKED_PLACE, canopy, CANOPY_CHECK_TTL));
        }
        return List.of(new ExploreAreaAction(14.0, 26.0));
    }

    /**
     * 할 일이 없을 때 주변을 둘러본다. 땅속에 있으면 하던 일(채굴)로 곧 돌아갈 수 있도록 지상으로 올라가지 않고 동굴 안을 둘러보며,
     * 동굴 안에서도 더 갈 곳이 없을 때만 올라간다.
     */
    static List<Action> explore(AIPlayer ai) {
        if (TerrainPlans.needsToClimb(ai)) {
            // 땅속에서 밤을 나는 중이면 돌아다니지도 올라가지도 않고 그 자리에서 아침을 기다린다.
            if (SurvivalPlans.tooLateToSurface(ai)) return List.of(new WaitAction(NIGHT_WAIT_TICKS));
            boolean caveDeadEnd = ai.getMemory().countRecentFailures("ExploreArea", ai.getTicks(), CAVE_RETRY_WINDOW) > 0;
            if (!caveDeadEnd) return List.of(ExploreAreaAction.cave(8.0, 20.0));
            List<Action> climb = TerrainPlans.climbOut(ai);
            if (!climb.isEmpty()) return climb;
        }
        return List.of(new ExploreAreaAction(14.0, 26.0));
    }

    /**
     * 구덩이나 나무 꼭대기처럼 걸어서 나갈 수 없는 곳에서 길을 만든다.
     * 쌓아 올린 블록 위면 캐면서 내려오고, 주변보다 낮은 곳(땅속, 굴, 구덩이)이면 위로, 높은 곳이면 아래로 길을 낸다.
     */
    public static List<Action> digWayOut(AIPlayer ai) {
        TreeJob job = ai.getTreeJob();
        if (job != null) {
            List<Action> down = TreePlans.descend(ai, job);
            if (!down.isEmpty()) return down;
        }
        // 물에 떠 있으면 계단을 팔 수도 블록을 쌓을 수도 없다. 먼저 마른 땅으로 나온다.
        List<Action> leaveWater = TerrainPlans.leaveWater(ai);
        if (!leaveWater.isEmpty()) return leaveWater;
        if (!ai.getBody().isGrounded()) return List.of();
        World world = ai.getPlayer().getWorld();
        BlockPoint feet = ai.getPosition();
        boolean low = TerrainPlans.isDeepUnderground(world, feet) || TerrainPlans.isLowerThanSurroundings(world, feet);
        return low ? TerrainPlans.climbOut(ai) : TerrainPlans.digStairDown(ai, true, false);
    }

    static List<Action> pickupItems(AIPlayer ai) {
        return List.of(new PickupItemAction(LOOSE_DROP_RADIUS, true));
    }

    // 베던 나무가 있으면 그 나무를 끝까지 베고, 없으면 가장 가까운 나무를 베기 시작한다.
    static List<Action> collectWood(AIPlayer ai) {
        TreeJob job = ai.getTreeJob();
        if (job != null) {
            List<Action> tree = TreePlans.continueTree(ai, job);
            if (!tree.isEmpty()) return tree;
        }
        BlockPoint log = ResourceLocator.locate(ai, MemoryType.TREE);
        if (log == null) return List.of();
        ai.debug("Target tree found at " + log);
        return mineBlock(ai, log);
    }

    static List<Action> mineIron(AIPlayer ai) {
        return mineOre(ai, MemoryType.IRON_ORE);
    }

    // 철을 찾는다. 철은 지상에 거의 없으므로 지상을 돌아다니지 않고 땅속으로 내려간다.
    static List<Action> findIron(AIPlayer ai) {
        return searchUnderground(ai, IRON_LEVEL, IRON_LEVEL_SLACK);
    }

    /**
     * 다이아몬드를 찾는다. 다이아몬드는 월드 바닥 가까이에 가장 많으므로 그 깊이까지 계단을 파 내려간 뒤 수평으로 굴을 판다.
     * 그 깊이의 동굴에는 용암이 고여 있으므로, 굴을 팔 때 용암이나 물에 닿는 칸은 건드리지 않는다(TerrainPlans).
     */
    static List<Action> findDiamond(AIPlayer ai) {
        int level = ai.getPlayer().getWorld().getMinHeight() + DIAMOND_ABOVE_BOTTOM;
        return searchUnderground(ai, level, DIAMOND_LEVEL_SLACK);
    }

    /**
     * 광물이 많이 나오는 높이(level)까지 내려가서 찾는다.
     * 이미 파 둔 굴이 있으면 그 굴로 내려가고, 동굴 안이면 동굴을 따라 걷고, 막다른 곳이면 직접 굴을 판다.
     *
     * @param slack 물이나 용암 때문에 더 내려갈 수 없을 때, 목표 높이보다 이만큼 위에서라도 수평 굴을 판다
     */
    private static List<Action> searchUnderground(AIPlayer ai, int level, int slack) {
        List<Action> leave = HomePlans.leaveBuilding(ai);
        if (!leave.isEmpty()) return leave;

        BlockPoint feet = ai.getPosition();
        World world = ai.getPlayer().getWorld();
        if (feet.y() > level) {
            List<Action> shaft = ShaftPlans.followShaftDown(ai);
            if (!shaft.isEmpty()) return shaft;
        }

        boolean inCave = TerrainPlans.isDeepUnderground(world, feet);
        // 동굴 탐험이 방금 실패했다면 더 걸어갈 곳이 없는 것이므로 파는 쪽으로 넘어간다.
        boolean caveDeadEnd = ai.getMemory().countRecentFailures("ExploreArea", ai.getTicks(), CAVE_RETRY_WINDOW) > 0;
        ShaftRegistry shafts = ai.getTeam().getShafts();
        boolean atFaceEnd = shafts.endsNear(ai.getWorldId(), feet, TUNNEL_FACE_RANGE);
        // 막다른 굴을 되돌아 나온 자리에서도 동굴을 돌아다니지 않고 판다. 거기서 옆으로 새 굴을 낸다.
        boolean atTunnelFace = atFaceEnd || shafts.isDeadEndStep(ai.getWorldId(), feet);
        if (inCave && !caveDeadEnd && !atTunnelFace) return List.of(ExploreAreaAction.cave(8.0, 20.0));

        // 굴에 물이 흘러들어 와서 물속에 서 있으면 먼저 물길을 막는다.
        if (ai.getPlayer().isInWater() && inCave) {
            List<Action> plug = plugWater(ai);
            if (!plug.isEmpty()) return plug;
            // 막을 원천이 없으면 물에서 나온다. 물에 떠 있는 채로는 굴을 팔 수 없다.
            List<Action> leaveWater = TerrainPlans.leaveWater(ai);
            if (!leaveWater.isEmpty()) return leaveWater;
        }

        List<Action> dig = feet.y() > level ? TerrainPlans.digStairDown(ai, false, true) : TerrainPlans.digTunnel(ai, true);
        // 물이나 용암 때문에 더 내려갈 수 없으면 그 높이에서 옆으로 굴을 판다. 목표 높이 근처면 거기서 광물을 찾는 것이고,
        // 아직 그보다 높은 땅속이면 막힌 자리를 비켜 가는 것이다 (한 칸 옮긴 뒤 다음 계획에서 다시 내려가 본다).
        // 높은 땅속에서 옆으로 파지 않으면, 사방이 물이나 용암에 닿은 자리에서 갈 길 없는 탐험만 되풀이하다가 멈춘다.
        if (dig.isEmpty() && DigRules.shouldTunnelSideways(feet.y(), level, slack, inCave)) {
            dig = TerrainPlans.digTunnel(ai, true);
            if (!dig.isEmpty() && feet.y() > level + slack) ai.debug("Cannot dig down from " + feet + ", tunnelling sideways to get around");
        }
        if (!dig.isEmpty()) return dig;
        // 물 때문에 팔 수 없는 자리라면, 가까운 물의 원천을 막는다.
        List<Action> plug = plugWater(ai);
        if (!plug.isEmpty()) return plug;
        // 굴의 끝에서 더 팔 수도, 물길을 막을 수도 없으면 그 굴은 막다른 굴이다. 잠깐 뒤에 한 번 더 확인하고,
        // 그래도 팔 수 없으면 몇 단 되돌아가서 다른 쪽으로 새로 판다.
        if (atFaceEnd && ai.getBody().isGrounded()) {
            List<Action> retreat = ShaftPlans.leaveDeadEnd(ai);
            if (!retreat.isEmpty()) return retreat;
        }
        // 원천이 근처에 없거나 용암 때문이라면 그 자리를 피해서 옮긴다.
        return List.of(inCave ? ExploreAreaAction.cave(8.0, 20.0) : new ExploreAreaAction(14.0, 26.0));
    }

    static List<Action> mineDiamond(AIPlayer ai) {
        return mineOre(ai, MemoryType.DIAMOND_ORE);
    }

    /**
     * 자갈을 캐서 부싯돌을 얻는다. 아는 자갈이 있으면 그것을 캐고, 없으면 가진 자갈을 놓았다가 다시 캔다
     * (놓은 자리는 기억해 두므로 다음 계획이 그것을 캔다). 자갈이 하나도 없으면 찾으러 다닌다.
     */
    static List<Action> gatherFlint(AIPlayer ai) {
        BlockPoint gravel = ResourceLocator.locate(ai, MemoryType.GRAVEL, ORE_WALK_RANGE);
        if (gravel != null) {
            ai.debug("Target GRAVEL found at " + gravel);
            return mineBlock(ai, gravel);
        }
        if (ai.getInventory().has(Material.GRAVEL)) return List.of(new PlaceBlockAction(Material.GRAVEL, MemoryType.GRAVEL));
        return explore(ai);
    }

    static List<Action> mineCoal(AIPlayer ai) {
        return mineOre(ai, MemoryType.COAL_ORE);
    }

    // 캐러 갈 광석을 알고 있는지. 걸어갈 수 있는 것이 없어도 굴을 파거나 다리를 놓아 닿을 만큼 가까운 것이 있으면 안다고 본다.
    public static boolean knowsOre(AIPlayer ai, MemoryType type) {
        return ResourceLocator.locate(ai, type, ORE_WALK_RANGE) != null
                || ResourceLocator.locateBlocked(ai, type, DIG_TOWARD_RANGE) != null;
    }

    // 바로 근처에 보이는 광석이 있는지. 광맥을 캐기 시작했으면 다 캘 때까지 이어서 캔다.
    public static boolean veinNearby(AIPlayer ai, MemoryType type) {
        return ResourceLocator.locate(ai, type, VEIN_RANGE) != null || ResourceLocator.locateBlocked(ai, type, VEIN_RANGE) != null;
    }

    private static List<Action> mineOre(AIPlayer ai, MemoryType type) {
        BlockPoint ore = ResourceLocator.locate(ai, type, ORE_WALK_RANGE);
        if (ore != null) {
            ai.debug("Target " + type + " found at " + ore);
            return mineBlock(ai, ore);
        }
        // 동굴 벽 너머나 협곡 건너편처럼 보이기는 하지만 걸어갈 수 없는 광석은, 굴을 파거나 다리를 놓아서 다가간다.
        BlockPoint blocked = ResourceLocator.locateBlocked(ai, type, DIG_TOWARD_RANGE);
        return blocked == null ? List.of() : approachBlocked(ai, blocked);
    }

    private static List<Action> approachBlocked(AIPlayer ai, BlockPoint target) {
        Player player = ai.getPlayer();
        // 손이 닿을 만큼 가까우면 바로 캔다. 앞을 가린 블록은 캐는 행동이 알아서 먼저 치운다.
        if (Positions.center(player.getWorld(), target).distance(player.getEyeLocation()) <= BREAK_REACH) {
            ai.getTeam().claim(ai, target);
            return List.of(BreakBlockAction.mine(target), new PickupItemAction(DROP_RADIUS, false));
        }
        ai.debug("Making a way toward " + target);
        return TerrainPlans.stepToward(ai, target, false);
    }

    // 구조물의 전리품 상자로 가서 연다.
    static List<Action> lootChest(AIPlayer ai) {
        BlockPoint chest = ResourceLocator.locate(ai, MemoryType.LOOT_CHEST, ORE_WALK_RANGE);
        if (chest == null) return List.of();
        ai.debug("Loot chest found at " + chest);
        return List.of(new MoveToAction(PathGoal.reach(chest, CHEST_REACH), true), new LootChestAction(chest));
    }

    /**
     * 가까운 물의 원천 블록을 가진 블록으로 막는다. 원천이 근처에 없거나 막을 블록이 없으면 빈 목록.
     * 흐르는 물만 있고 원천이 멀리 있다면 막을 수 없으므로, 그때는 물을 피해 다른 곳으로 간다.
     */
    private static List<Action> plugWater(AIPlayer ai) {
        if (ai.getInventory().findFillerSlot() < 0) return List.of();

        World world = ai.getPlayer().getWorld();
        BlockPoint feet = ai.getPosition();
        BlockPoint source = null;
        double best = Double.MAX_VALUE;
        int sources = 0;
        for (int dx = -WATER_SEARCH_RADIUS; dx <= WATER_SEARCH_RADIUS; dx++) {
            for (int dz = -WATER_SEARCH_RADIUS; dz <= WATER_SEARCH_RADIUS; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPoint point = feet.offset(dx, dy, dz);
                    if (!isWaterSource(world, point)) continue;
                    sources++;
                    double distance = point.distanceSq(feet);
                    if (distance >= best) continue;
                    best = distance;
                    source = point;
                }
            }
        }
        // 원천이 많다면 호수나 강이다. 블록을 다 써도 막을 수 없으니 막지 않고 피해 간다.
        if (source == null || sources > MAX_SOURCES_TO_PLUG) return List.of();

        ai.debug("Plugging water source at " + source);
        ai.getTeam().say(ai, Phrases.pluggingWater(), false);
        return List.of(new MoveToAction(PathGoal.reach(source, BREAK_REACH), false), new PlugWaterAction(source));
    }

    private static boolean isWaterSource(World world, BlockPoint point) {
        if (point.y() < world.getMinHeight() || point.y() >= world.getMaxHeight() || !Positions.isLoaded(world, point)) return false;
        Block block = Positions.block(world, point);
        return block.getType() == Material.WATER && block.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0;
    }

    static List<Action> mineStone(AIPlayer ai) {
        // 멀리 있는 돌은 절벽이나 물 건너편이라 갈 수 없는 경우가 많다. 가까운 돌만 캐러 가고,
        // 방금 길을 찾지 못했다면 남은 돌을 하나씩 시도하는 대신 바로 땅을 판다. 돌은 땅을 몇 칸만 파면 나온다.
        // 캐려던 돌이 계단 발판에 가려 있어서 캘 수 없었을 때도 마찬가지다. 옆의 돌도 같은 발판에 가려 있기 쉽다.
        boolean pathFailedRecently = ai.getMemory().countRecentFailures("MoveTo", ai.getTicks(), STONE_RETRY_WINDOW) > 0
                || ai.getMemory().countRecentFailures("BreakBlock", ai.getTicks(), STONE_RETRY_WINDOW) > 0;
        BlockPoint stone = pathFailedRecently ? null : ResourceLocator.locate(ai, MemoryType.STONE, STONE_WALK_RANGE);
        if (stone != null) {
            ai.debug("Target stone found at " + stone);
            return mineBlock(ai, stone);
        }

        // 이미 파 둔 굴이 있으면 그 굴로 내려가서 벽의 돌을 캔다.
        List<Action> shaft = ShaftPlans.followShaftDown(ai);
        if (!shaft.isEmpty()) return shaft;
        List<Action> leave = HomePlans.leaveBuilding(ai);
        if (!leave.isEmpty()) return leave;

        List<Action> dig = TerrainPlans.digStairDown(ai, false, true);
        if (!dig.isEmpty()) return dig;
        List<Action> plug = plugWater(ai);
        if (!plug.isEmpty()) return plug;
        // 물가처럼 팔 수 없는 자리라면 조금 먼 돌이라도 찾아가 본다.
        stone = ResourceLocator.locate(ai, MemoryType.STONE);
        return stone == null ? List.of() : mineBlock(ai, stone);
    }

    // 다가가서, 캐고, 떨어진 아이템을 줍는다.
    private static List<Action> mineBlock(AIPlayer ai, BlockPoint target) {
        // 동료가 같은 곳을 노리지 않도록 맡아 둔다.
        ai.getTeam().claim(ai, target);
        return List.of(
                new MoveToAction(PathGoal.reach(target, BREAK_REACH), true),
                BreakBlockAction.mine(target),
                new PickupItemAction(DROP_RADIUS, false)
        );
    }
}
