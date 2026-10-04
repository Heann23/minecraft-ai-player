package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.navigation.AStarSearch;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.TerrainView;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 용암, 불, 물속처럼 그 자리에 있으면 죽는 곳에서 빠져나온다.
 * 급한 위험에서는 가까운 안전한 칸으로 헤엄친다. 평소 물에서 나올 때는 미리 확인한 둑까지 경로를 따라간다.
 */
public final class EscapeHazardAction extends AbstractAction {
    private static final int TIMEOUT = 200;
    private static final int SEARCH_RADIUS = 6;
    private static final int SEARCH_HEIGHT = 4;
    private static final int RETARGET_INTERVAL = 20;
    // 숨이 이만큼 찼으면 익사 위험에서 벗어난 것으로 본다.
    private static final double SAFE_AIR_RATIO = 0.8;

    // 위험하지는 않지만 물에서 나와 마른 땅에 서려는 것인지. 물속에서는 블록을 놓을 자리가 없고 캐는 속도도 5배 느리다.
    private final boolean leavingWater;
    private BlockPoint safeSpot;

    public EscapeHazardAction() {
        this("EscapeHazard", false);
    }

    private EscapeHazardAction(String name, boolean leavingWater) {
        super(name, TIMEOUT);
        this.leavingWater = leavingWater;
    }

    // 물에서 나와 가장 가까운 마른 땅에 선다. 근처에 마른 땅이 없으면 실패한다.
    public static EscapeHazardAction leaveWater(BlockPoint shore) {
        EscapeHazardAction action = new EscapeHazardAction("LeaveWater", true);
        action.safeSpot = shore;
        return action;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        if (leavingWater) ai.getNavigation().navigateTo(PathGoal.arrive(safeSpot, 0.3));
        else safeSpot = findSafeSpot(ai.getPlayer(), false);
        if (safeSpot != null) ai.debug((leavingWater ? "Leaving the water for " : "Escaping to ") + safeSpot);
        else if (leavingWater) fail("no dry ground nearby");
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (leavingWater ? !player.isInWater() && ai.getBody().isGrounded() : isSafe(ai, player)) {
            succeed();
            return;
        }
        if (leavingWater) {
            NavigationSystem navigation = ai.getNavigation();
            navigation.tick();
            if (navigation.getState() == NavigationSystem.State.FAILED) fail(navigation.getFailReason());
            // 블록 좌표는 도착했어도 물리적으로 아직 둑 위에 올라서는 중일 수 있다.
            if (navigation.getState() == NavigationSystem.State.ARRIVED) ai.getBody().inputJump(true);
            return;
        }
        if (getElapsed() % RETARGET_INTERVAL == 0) safeSpot = findSafeSpot(player, leavingWater);

        AIBody body = ai.getBody();
        // 용암이나 물속에서는 점프 입력이 위로 헤엄치는 동작이 된다.
        body.inputJump(true);
        body.inputSprint(false);
        if (safeSpot == null) {
            body.inputMove(0.0F, 0.0F);
            return;
        }

        Location location = player.getLocation();
        double dx = safeSpot.x() + 0.5 - location.getX();
        double dz = safeSpot.z() + 0.5 - location.getZ();
        if (dx * dx + dz * dz > 0.04) {
            body.inputLook(Positions.yawTo(dx, dz), 0.0F);
            body.inputMove(1.0F, 0.0F);
        } else {
            body.inputMove(0.0F, 0.0F);
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        if (leavingWater) ai.getNavigation().stop();
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputJump(false);
    }

    // 인식 결과는 판단 주기마다만 갱신되므로, 여기서는 플레이어의 현재 상태를 직접 확인한다.
    private static boolean isSafe(AIPlayer ai, Player player) {
        Perception perception = ai.getPerception();
        boolean breathing = player.getRemainingAir() >= player.getMaximumAir() * SAFE_AIR_RATIO;
        return !player.isInLava() && !perception.isStandingInDanger() && breathing;
    }

    private static @Nullable BlockPoint findSafeSpot(Player player, boolean dryFeet) {
        TerrainView terrain = new BukkitTerrainView(player.getWorld());
        Location location = player.getLocation();
        int px = location.getBlockX();
        int py = location.getBlockY();
        int pz = location.getBlockZ();

        BlockPoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
                    // 위로 올라가는 것은 수평 이동보다 어렵다.
                    double distance = dx * dx + dz * dz + dy * dy * 2.0;
                    if (distance >= bestDistance) continue;
                    int x = px + dx;
                    int y = py + dy;
                    int z = pz + dz;
                    if (!isSafeSpot(terrain, x, y, z)) continue;
                    // 물에서 나오려는 것이면 발이 물에 잠기는 칸은 고르지 않는다.
                    if (dryFeet && terrain.classify(x, y, z) != BlockClass.OPEN) continue;
                    best = new BlockPoint(x, y, z);
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static boolean isSafeSpot(TerrainView terrain, int x, int y, int z) {
        if (terrain.classify(x, y + 1, z) != BlockClass.OPEN) return false;
        if (!AStarSearch.isStandable(terrain, x, y, z)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (terrain.classify(x + dx, y, z + dz) == BlockClass.DANGER) return false;
                if (terrain.classify(x + dx, y - 1, z + dz) == BlockClass.DANGER) return false;
            }
        }
        return true;
    }
}
