package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PlaceFillerAction;
import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.Enclosure;
import me.herry.minecraftAI.ai.navigation.RefugeSearch;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 감당할 수 없는 몬스터에게 몰렸을 때 벽이나 땅을 파고 들어가 숨는 계획.
 * 동굴 안에서는 달아나도 길이 좁고 막다른 곳이 많아서 따라잡히기 쉽다. 숨은 뒤에는 그 안에서 먹고 쉬어서 회복한다.
 */
public final class RefugePlans {
    private static final long FLEE_FAIL_WINDOW = 300L;
    // 막으려는 칸에 몬스터가 서 있으면 블록을 놓을 수 없다. 방금 그렇게 실패했으면 다시 숨으려 하지 않고 달아나거나 맞선다.
    private static final long SEAL_FAIL_WINDOW = 100L;
    // 이 거리 안에 몬스터가 있는 빈 곳으로는, 보이지 않아도 뚫지 않는다.
    private static final double COVER_RANGE = 6.0;

    private RefugePlans() {
    }

    /**
     * 몬스터가 닿지 못하는 곳에 숨어 있는지. 사방이 막힌 작은 공간 안에 있고 그 안에 몬스터가 없으면 그렇다.
     * 벽 너머의 몬스터가 나를 노리고 있더라도 걸어 들어오지도 보지도 못하므로 위협이 아니다.
     */
    public static boolean isHidden(AIPlayer ai) {
        // 가까이에 몬스터가 없으면 따질 필요가 없다. 빈칸을 따라가 보는 계산이라 필요할 때만 한다.
        List<LivingEntity> hostiles = ai.getPerception().getHostiles();
        if (hostiles.isEmpty()) return false;
        Enclosure.Space space = Enclosure.around(new BukkitTerrainView(ai.getPlayer().getWorld()), ai.getPosition());
        if (!space.closed()) return false;
        Set<BlockPoint> inside = space.cells();
        for (LivingEntity hostile : hostiles) {
            BlockPoint cell = Positions.of(hostile.getLocation());
            if (inside.contains(cell) || inside.contains(cell.offset(0, 1, 0))) return false;
        }
        return true;
    }

    // 숨을 자리가 없거나 숨을 상황이 아니면 빈 목록.
    static List<Action> digIn(AIPlayer ai, @Nullable LivingEntity threat) {
        if (!ai.getBody().isGrounded() || ai.getPlayer().isInWater()) return List.of();
        long now = ai.getTicks();
        boolean fleeFailed = ai.getMemory().countRecentFailures("RunAway", now, FLEE_FAIL_WINDOW) > 0;
        int blocks = ai.getInventory().count(InventorySystem::isFiller);
        if (!CombatSystem.shouldTakeRefuge(TerrainPlans.needsToClimb(ai), fleeFailed, blocks)) return List.of();
        if (ai.getMemory().countRecentFailures("PlaceFiller", now, SEAL_FAIL_WINDOW) > 0) return List.of();

        World world = ai.getPlayer().getWorld();
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        Base home = ai.getWorldModel().homeIn(world.getUID());
        BlockPoint feet = ai.getPosition();
        BlockPoint from = threat == null ? null : Positions.of(threat.getLocation());
        RefugeSearch.Refuge refuge = RefugeSearch.find(terrain, feet, from, block ->
                Positions.block(world, block).getType().getHardness() >= 0.0F
                        && (home == null || !home.isInsideBuilding(world.getUID(), block))
                        && !TerrainPlans.cutsShaft(ai, world, terrain, block), blocks);
        if (refuge == null) return List.of();

        ai.debug("Digging in at " + refuge.cell() + " (breaking " + refuge.toBreak().size() + ", sealing " + refuge.toSeal().size() + ")");
        ai.getTeam().say(ai, Phrases.takingRefuge(), false);
        ai.getCombatMemory().onRefuge(now);
        List<Action> actions = new ArrayList<>();
        for (BlockPoint block : refuge.toBreak()) actions.add(new BreakBlockAction(block));
        actions.add(MoveToAction.enterCell(refuge.cell()));
        for (BlockPoint block : refuge.toSeal()) actions.add(new PlaceFillerAction(block));
        // 나중에 굴을 이어 팔 때 몬스터가 있던 쪽이 아니라 숨어든 쪽으로 판다.
        BlockFace direction = faceOf(refuge.cell().x() - feet.x(), refuge.cell().z() - feet.z());
        if (direction != null) ai.setDigDirection(direction);
        return actions;
    }

    private static @Nullable BlockFace faceOf(int dx, int dz) {
        if (dx > 0) return BlockFace.EAST;
        if (dx < 0) return BlockFace.WEST;
        if (dz > 0) return BlockFace.SOUTH;
        return dz < 0 ? BlockFace.NORTH : null;
    }

    /**
     * 몬스터가 있는 다른 빈 곳으로 새로 뚫리는 블록인지. 숨은 지 얼마 안 됐을 때만 따진다.
     * 가까이(6칸 안)에 몬스터가 있는 곳으로 뚫으면 그 틈으로 보고 때리거나 들어온다.
     * 더 멀리 있어도 그 틈으로 서로 보이는 몬스터는 이쪽을 보고 다가오고, 감당할 수 없어서 숨었던 상대라면 다시 숨어야 한다.
     * (틈을 냄 → 멀리 있는 좀비가 보임 → 다시 막음을 6초마다 10분 넘게 되풀이한 일이 있었다.)
     */
    static boolean breaksCover(AIPlayer ai, BukkitTerrainView terrain, BlockPoint... blocks) {
        List<LivingEntity> hostiles = ai.getPerception().getHostiles();
        if (hostiles.isEmpty() || !ai.getCombatMemory().isWaryAfterRefuge(ai.getTicks())) return false;
        // 지금 내 공간과 이미 이어져 있는 빈칸으로 넓히는 것은 새로 뚫는 것이 아니다.
        Set<BlockPoint> inside = Enclosure.around(terrain, ai.getPosition()).cells();
        List<BlockPoint> openings = Enclosure.openings(terrain, inside, List.of(blocks));
        if (openings.isEmpty()) return false;
        Visibility.Opacity opacity = PerceptionSystem.opacityOf(ai.getPlayer().getWorld());
        double sightRange = ai.getConfig().engageRange;
        for (BlockPoint opening : openings) {
            for (LivingEntity hostile : hostiles) {
                double distance = Positions.of(hostile.getLocation()).distance(opening);
                if (distance <= COVER_RANGE) return true;
                if (distance > sightRange) continue;
                Location eye = hostile.getEyeLocation();
                if (Visibility.canSeePoint(opacity, eye.getX(), eye.getY(), eye.getZ(),
                        opening.x() + 0.5, opening.y() + 0.5, opening.z() + 0.5)) return true;
            }
        }
        return false;
    }
}
