package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;

/**
 * 제자리에서 한 번 점프한다.
 */
public final class JumpAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 30;
    private static final int PRESS_TICKS = 2;
    private static final int MIN_AIR_TICKS = 5;

    public JumpAction() {
        super("Jump", TIMEOUT);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ai.getBody().inputJump(true);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        if (getElapsed() > PRESS_TICKS) ai.getBody().inputJump(false);
        if (getElapsed() >= MIN_AIR_TICKS && ai.getBody().isGrounded()) succeed();
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputJump(false);
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.JUMP;
    }
}
