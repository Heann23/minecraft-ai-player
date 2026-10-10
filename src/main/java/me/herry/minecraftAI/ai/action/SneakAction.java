package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;

import java.util.UUID;

/** A finite crouch input; it never leaves a persistent stance behind. */
public final class SneakAction extends AbstractAction implements PrimitiveAction {
    private final int ticks;
    private UUID world;

    public SneakAction(int ticks) {
        super("Sneak", duration(ticks));
        this.ticks = ticks;
    }

    private static int duration(int ticks) {
        if (ticks < 1 || ticks > 400) throw new IllegalArgumentException("sneak ticks must be 1..400");
        return ticks;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getWorldId();
        ai.getNavigation().stop();
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputJump(false);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        if (!ai.getBody().isUsable() || !world.equals(ai.getWorldId())) {
            fail("body unavailable or world changed");
            return;
        }
        ai.getBody().inputSneak(true);
        if (getElapsed() >= ticks) succeed();
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputSneak(false);
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.SNEAK;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Ticks(ticks);
    }
}
