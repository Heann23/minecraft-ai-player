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
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.navigation.RefugeSearch;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
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
        if (!refuge.cell().equals(feet)) actions.add(new MoveToAction(PathGoal.arrive(refuge.cell(), 0.3), false));
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
}
