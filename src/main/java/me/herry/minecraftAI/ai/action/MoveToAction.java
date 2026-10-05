package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.navigation.NavigationSystem;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;

/**
 * 목표 지점까지 걸어서 이동한다.
 */
public final class MoveToAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 1200;

    private final PathGoal goal;
    private final boolean rememberUnreachable;

    /**
     * @param rememberUnreachable 길을 찾지 못했을 때 그 대상을 "갈 수 없는 곳"으로 기억할지
     */
    public MoveToAction(PathGoal goal, boolean rememberUnreachable) {
        super("MoveTo", TIMEOUT);
        this.goal = goal;
        this.rememberUnreachable = rememberUnreachable;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ai.getNavigation().navigateTo(goal);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        NavigationSystem navigation = ai.getNavigation();
        navigation.tick();

        if (navigation.getState() == NavigationSystem.State.ARRIVED) {
            if (rememberUnreachable) ai.getMemory().clearUnreachable(ai.getWorldId(), goal.target());
            succeed();
        } else if (navigation.getState() == NavigationSystem.State.FAILED) {
            // 같은 곳에서 거듭 실패하면 점점 더 오래 피한다.
            if (rememberUnreachable) ai.getMemory().rememberUnreachable(ai.getWorldId(), goal.target(), ai.getTicks());
            fail(navigation.getFailReason());
        }
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
