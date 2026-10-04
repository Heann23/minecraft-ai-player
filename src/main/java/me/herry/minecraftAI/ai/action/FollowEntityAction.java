package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import org.bukkit.entity.Entity;

/**
 * 정해진 시간 동안 엔티티를 일정 거리를 두고 따라간다.
 */
public final class FollowEntityAction extends AbstractAction {
    private final Entity target;
    private final double distance;
    private final int durationTicks;
    private final EntityChaser chaser = new EntityChaser();

    public FollowEntityAction(Entity target, double distance, int durationTicks) {
        super("FollowEntity", durationTicks + 20);
        this.target = target;
        this.distance = distance;
        this.durationTicks = durationTicks;
    }

    @Override
    protected void onStart(AIPlayer ai) {
    }

    @Override
    protected void onTick(AIPlayer ai) {
        if (!target.isValid() || target.isDead() || !target.getWorld().equals(ai.getPlayer().getWorld())) {
            fail("target disappeared");
            return;
        }
        if (getElapsed() >= durationTicks) {
            succeed();
            return;
        }

        EntityChaser.Result result = chaser.tick(ai, target, distance);
        if (result == EntityChaser.Result.UNREACHABLE) {
            fail("unreachable");
        } else if (result == EntityChaser.Result.IN_RANGE) {
            ai.getBody().inputMove(0.0F, 0.0F);
            ai.getBody().inputJump(ai.getPlayer().isInWater());
            ai.getBody().lookAt(target.getLocation().getX(), target.getLocation().getY() + target.getHeight() * 0.8, target.getLocation().getZ());
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getNavigation().stop();
    }
}
