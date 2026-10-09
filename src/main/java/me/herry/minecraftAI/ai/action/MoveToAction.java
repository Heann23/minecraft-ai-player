package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.UUID;

/**
 * 목표 지점까지 걸어서 이동한다.
 */
public final class MoveToAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 1200;
    private static final int CELL_ENTRY_TIMEOUT = 20;
    private static final double CELL_EDGE_MARGIN = 0.025;
    private static final double FLOOR_TOLERANCE = 0.1;
    private static final float ENTRY_FACING_TOLERANCE = 30.0F;

    private final PathGoal goal;
    private final boolean rememberUnreachable;
    private final boolean enterFully;
    private UUID entryWorld;
    private int entryTicks;

    /**
     * @param rememberUnreachable 길을 찾지 못했을 때 그 대상을 "갈 수 없는 곳"으로 기억할지
     */
    public MoveToAction(PathGoal goal, boolean rememberUnreachable) {
        this(goal, rememberUnreachable, false);
    }

    private MoveToAction(PathGoal goal, boolean rememberUnreachable, boolean enterFully) {
        super("MoveTo", TIMEOUT);
        this.goal = goal;
        this.rememberUnreachable = rememberUnreachable;
        this.enterFully = enterFully;
    }

    /**
     * 입구를 막기 전에 몸 전체가 피신 칸 안에 들어오도록 한다. 일반 이동의 도착 판정은 바꾸지 않는다.
     */
    public static MoveToAction enterCell(BlockPoint cell) {
        return new MoveToAction(PathGoal.arrive(cell, 0.3), false, true);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        if (enterFully) entryWorld = ai.getWorldId();
        ai.getNavigation().navigateTo(goal);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        if (enterFully && !entryWorld.equals(ai.getWorldId())) {
            fail("world changed while entering cell");
            return;
        }
        NavigationSystem navigation = ai.getNavigation();
        navigation.tick();

        if (navigation.getState() == NavigationSystem.State.ARRIVED) {
            if (enterFully && !finishCellEntry(ai)) return;
            if (rememberUnreachable) ai.getMemory().clearUnreachable(ai.getWorldId(), goal.target());
            succeed();
        } else if (navigation.getState() == NavigationSystem.State.FAILED) {
            // 같은 곳에서 거듭 실패하면 점점 더 오래 피한다.
            if (rememberUnreachable) ai.getMemory().rememberUnreachable(ai.getWorldId(), goal.target(), ai.getTicks());
            fail(navigation.getFailReason());
        }
    }

    private boolean finishCellEntry(AIPlayer ai) {
        Player player = ai.getPlayer();
        BlockPoint cell = goal.target();
        if (ai.getBody().isGrounded() && fitsInsideCell(player.getBoundingBox(), cell)) {
            ai.debug("Fully entered refuge cell at " + cell);
            return true;
        }
        if (++entryTicks > CELL_ENTRY_TIMEOUT) {
            fail("could not fully enter refuge cell");
            return false;
        }

        // Navigation은 발의 블록 좌표로 도착을 판정해, 경계를 넘은 직후에는 몸이 입구에 남아 있다.
        // 입구 쪽으로 돌아서 뒷걸음질하지 않고 칸의 중심까지 실제로 걸어 들어간 뒤 멈춘다.
        ai.getBody().inputSprint(false);
        ai.getBody().inputJump(false);
        Location location = player.getLocation();
        if (!ai.getBody().isGrounded() || Math.abs(location.getY() - cell.y()) > FLOOR_TOLERANCE) {
            ai.getBody().inputMove(0.0F, 0.0F);
            return false;
        }
        double x = cell.x() + 0.5;
        double z = cell.z() + 0.5;
        double eyeY = player.getEyeLocation().getY();
        ai.getBody().lookAt(x, eyeY, z);
        boolean facing = ai.getBody().isFacing(x, eyeY, z, ENTRY_FACING_TOLERANCE);
        ai.getBody().inputMove(facing ? 1.0F : 0.0F, 0.0F);
        return false;
    }

    static boolean fitsInsideCell(BoundingBox bounds, BlockPoint cell) {
        return bounds.getMinX() >= cell.x() + CELL_EDGE_MARGIN
                && bounds.getMaxX() <= cell.x() + 1.0 - CELL_EDGE_MARGIN
                && bounds.getMinZ() >= cell.z() + CELL_EDGE_MARGIN
                && bounds.getMaxZ() <= cell.z() + 1.0 - CELL_EDGE_MARGIN
                && bounds.getMinY() >= cell.y() - FLOOR_TOLERANCE
                && bounds.getMaxY() <= cell.y() + 2.0;
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getNavigation().stop();
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.MOVE_TO;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(goal.target());
    }
}
