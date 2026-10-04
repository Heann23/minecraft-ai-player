package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;

/**
 * 지금 서 있는 칸의 한가운데로 몸을 옮긴다.
 * 경로 이동은 칸 단위로만 도착을 판정해서 칸의 가장자리에 설 수 있는데, 그러면 몸이 옆 칸에 걸쳐서
 * 바로 옆 칸에 블록(상자, 침대 등)을 놓을 수 없다. 건축처럼 한자리에서 주변 칸을 채우는 일을 하기 전에 쓴다.
 */
public final class CenterOnBlockAction extends AbstractAction {
    private static final int TIMEOUT = 60;
    private static final double CENTERED_SQ = 0.15 * 0.15;
    // 이보다 멀리 있으면 가운데로 걸어갈 일이 아니라 경로 이동을 해야 한다.
    private static final double MAX_DISTANCE_SQ = 1.5 * 1.5;
    private static final float FACING_TOLERANCE = 30.0F;

    private final BlockPoint cell;

    public CenterOnBlockAction(BlockPoint cell) {
        super("CenterOnBlock", TIMEOUT);
        this.cell = cell;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ai.getBody().inputSprint(false);
        ai.getBody().inputJump(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Location location = ai.getPlayer().getLocation();
        double dx = cell.x() + 0.5 - location.getX();
        double dz = cell.z() + 0.5 - location.getZ();
        double distanceSq = dx * dx + dz * dz;
        if (distanceSq <= CENTERED_SQ) {
            succeed();
            return;
        }
        if (distanceSq > MAX_DISTANCE_SQ) {
            fail("too far from the block");
            return;
        }

        float yaw = Positions.yawTo(dx, dz);
        ai.getBody().inputLook(yaw, 0.0F);
        boolean facing = Math.abs(Positions.angleDifference(location.getYaw(), yaw)) < FACING_TOLERANCE;
        // 지나치지 않도록 웅크린 채로 천천히 다가간다.
        ai.getBody().inputSneak(true);
        ai.getBody().inputMove(facing ? 1.0F : 0.0F, 0.0F);
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSneak(false);
    }
}
