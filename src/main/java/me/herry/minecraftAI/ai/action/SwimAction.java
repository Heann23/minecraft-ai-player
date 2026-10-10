package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Objects;

/** Swims through water using the same movement, look, sprint and jump inputs as a player. */
public final class SwimAction extends AbstractAction implements PrimitiveAction {
    private static final double ARRIVAL_RADIUS_SQ = 1.0;
    private static final double MAX_DISTANCE_SQ = 16.0 * 16.0;
    private static final int MIN_AIR = 60;
    private final PrimitiveTarget.Point target;
    private World world;
    private double checkpointDistance;
    private int stalled;

    public SwimAction(PrimitiveTarget.Point target, int timeoutTicks) {
        super("Swim", duration(timeoutTicks));
        this.target = Objects.requireNonNull(target, "target");
        if (!Double.isFinite(target.x()) || !Double.isFinite(target.y()) || !Double.isFinite(target.z())) {
            throw new IllegalArgumentException("swim target must be finite");
        }
    }

    private static int duration(int ticks) {
        if (ticks < 1 || ticks > 400) throw new IllegalArgumentException("swim timeout must be 1..400");
        return ticks;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        Player player = ai.getPlayer();
        world = player.getWorld();
        ai.getNavigation().stop();
        if (!validTarget() || !player.isInWater() || distanceSquared(player) > MAX_DISTANCE_SQ) {
            fail("swim requires nearby loaded water");
            return;
        }
        ai.getBody().inputSneak(false);
        checkpointDistance = distanceSquared(player);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!ai.getBody().isUsable() || !player.getWorld().equals(world)) {
            fail("body unavailable or world changed");
            return;
        }
        if (!validTarget()) {
            fail("swim target changed");
            return;
        }
        if (player.getRemainingAir() <= MIN_AIR) {
            fail("insufficient air");
            return;
        }
        double distance = distanceSquared(player);
        if (distance <= ARRIVAL_RADIUS_SQ) {
            succeed();
            return;
        }
        if (!player.isInWater()) {
            fail("left water before arrival");
            return;
        }
        if (getElapsed() % 20 == 0) {
            stalled = distance >= checkpointDistance - 0.1 ? stalled + 20 : 0;
            checkpointDistance = distance;
            if (stalled >= 60) {
                fail("swim blocked");
                return;
            }
        }
        // Aim near the destination's torso so the swimming pose does not dive below a surface target.
        ai.getBody().lookAt(target.x(), target.y() + 0.6, target.z());
        boolean facing = ai.getBody().isFacing(target.x(), target.y() + 0.6, target.z(), 35.0F);
        ai.getBody().inputMove(facing ? 1.0F : 0.0F, 0.0F);
        ai.getBody().inputSprint(facing && player.getFoodLevel() > 6);
        ai.getBody().inputJump(player.getRemainingAir() < 120 || target.y() - player.getLocation().getY() > 0.4);
    }

    private boolean validTarget() {
        if (target.y() < world.getMinHeight() || target.y() >= world.getMaxHeight()) return false;
        int x = (int) Math.floor(target.x());
        int z = (int) Math.floor(target.z());
        return world.isChunkLoaded(x >> 4, z >> 4)
                && world.getBlockAt(x, (int) Math.floor(target.y()), z).getType() == Material.WATER;
    }

    private double distanceSquared(Player player) {
        Location location = player.getLocation();
        double dx = target.x() - location.getX();
        double dy = target.y() - location.getY();
        double dz = target.z() - location.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputJump(false);
        ai.getBody().inputSprint(false);
        ai.getBody().inputSneak(false);
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.SWIM;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return target;
    }
}
