package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.util.BlockPoint;

/**
 * 지정한 지점을 바라본다.
 */
public final class LookAtAction extends AbstractAction {
    private static final int TIMEOUT = 40;
    private static final float TOLERANCE = 8.0F;

    private final double x;
    private final double y;
    private final double z;

    public LookAtAction(double x, double y, double z) {
        super("LookAt", TIMEOUT);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static LookAtAction block(BlockPoint block) {
        return new LookAtAction(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ai.getBody().lookAt(x, y, z);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        ai.getBody().lookAt(x, y, z);
        if (ai.getBody().isFacing(x, y, z, TOLERANCE)) succeed();
    }
}
