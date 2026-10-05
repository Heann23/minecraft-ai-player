package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 포탈 칸으로 걸어 들어가서 서 있는다. 4초쯤 서 있으면 서버가 다른 차원으로 옮겨 준다 (순간이동이 아니라 포탈의 정상 동작이다).
 * 다른 행동들과 반대로 월드가 바뀌면 성공이다. 다만 월드가 바뀌는 순간 두뇌가 초기화되므로, 도착한 뒤의 기록은
 * 이 행동이 아니라 월드 이동 이벤트(PortalTracker)가 한다.
 *
 * 방금 포탈로 도착해서 이미 포탈 안에 서 있다면, 한 번 밖으로 나갔다가 다시 들어가야 서버가 옮겨 준다.
 */
public final class EnterPortalAction extends AbstractAction {
    private static final int TIMEOUT = 400;
    // 포탈 밖에 이만큼 있다가 다시 들어간다.
    private static final int OUTSIDE_TICKS = 15;
    // 한쪽으로 나가려는데 이만큼 지나도 못 나갔으면 반대쪽으로 나간다.
    private static final int FLIP_TICKS = 30;

    private final BlockPoint portal;
    private World world;
    private boolean leaving;
    private int leavingTicks;
    private int outsideTicks;
    private int side = 1;

    public EnterPortalAction(BlockPoint portal) {
        super("EnterPortal", TIMEOUT);
        this.portal = portal;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        Player player = ai.getPlayer();
        world = player.getWorld();
        leaving = isInPortal(player);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world)) {
            succeed();
            return;
        }
        if (!Positions.isLoaded(world, portal) || Positions.block(world, portal).getType() != Material.NETHER_PORTAL) {
            fail("no portal");
            return;
        }
        AIBody body = ai.getBody();
        Location location = player.getLocation();
        boolean inside = isInPortal(player);
        // 포탈의 틀이 동서로 서 있으면 남북으로 드나든다.
        boolean alongX = isFrameOrPortal(portal.offset(1, 0, 0)) || isFrameOrPortal(portal.offset(-1, 0, 0));

        if (leaving) {
            if (!inside && ++outsideTicks >= OUTSIDE_TICKS) {
                leaving = false;
                return;
            }
            if (inside && ++leavingTicks % FLIP_TICKS == 0) side = -side;
            double x = portal.x() + 0.5 + (alongX ? 0.0 : 2.0 * side);
            double z = portal.z() + 0.5 + (alongX ? 2.0 * side : 0.0);
            walkToward(body, location, x, z, inside);
            return;
        }

        if (inside) {
            // 들어섰으면 가만히 서서 기다린다.
            body.inputMove(0.0F, 0.0F);
            body.inputJump(false);
            return;
        }
        // 포탈 칸이 발보다 한 칸 높으면(틀의 아랫줄 위) 뛰어올라 들어간다.
        boolean stepUp = portal.y() > location.getY() + 0.1;
        walkToward(body, location, portal.x() + 0.5, portal.z() + 0.5, true);
        body.inputJump(stepUp && body.isGrounded() || body.isBlockedHorizontally());
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputJump(false);
    }

    private void walkToward(AIBody body, Location location, double x, double z, boolean move) {
        double dx = x - location.getX();
        double dz = z - location.getZ();
        boolean close = dx * dx + dz * dz < 0.04;
        if (!close) body.inputLook(Positions.yawTo(dx, dz), 0.0F);
        body.inputMove(move && !close ? 1.0F : 0.0F, 0.0F);
        body.inputJump(false);
    }

    private static boolean isInPortal(Player player) {
        return player.getLocation().getBlock().getType() == Material.NETHER_PORTAL;
    }

    private boolean isFrameOrPortal(BlockPoint cell) {
        Material type = Positions.block(world, cell).getType();
        return type == Material.NETHER_PORTAL || type == Material.OBSIDIAN;
    }
}
