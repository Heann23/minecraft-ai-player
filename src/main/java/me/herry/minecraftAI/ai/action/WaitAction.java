package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;

/**
 * 정해진 시간 동안 제자리에서 기다린다.
 */
public final class WaitAction extends AbstractAction {
    private final int ticks;

    public WaitAction(int ticks) {
        super("Wait", ticks + 20);
        this.ticks = ticks;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        // 물속에서 기다리면 가라앉으므로 떠 있는다.
        ai.getBody().inputJump(ai.getPlayer().isInWater());
        if (getElapsed() >= ticks) succeed();
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputJump(false);
    }
}
