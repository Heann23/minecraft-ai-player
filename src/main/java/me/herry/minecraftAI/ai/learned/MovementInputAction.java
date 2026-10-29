package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.AbstractAction;
import me.herry.minecraftAI.ai.primitive.*;
import org.bukkit.potion.PotionEffectType;

/** Selected player keys on land, in air or fluid. No terrain/risk/destination selection. Completion is input duration only. */
final class MovementInputAction extends AbstractAction implements PrimitiveAction {
    private final MovementInput input;
    private final int ticks;
    private int heldTicks;
    private boolean phaseObserved;
    private boolean lastInWater;
    private boolean lastGrounded;
    MovementInputAction(MovementInput input, int ticks) {
        super("MovementInput", duration(ticks) + 1);
        this.input = java.util.Objects.requireNonNull(input); this.ticks = ticks;
    }
    private static int duration(int ticks) {
        if (ticks < 1 || ticks > 600) throw new IllegalArgumentException("movement input ticks must be 1..600");
        return ticks;
    }
    @Override protected void onStart(AIPlayer ai) {
        ai.getNavigation().stop(); ai.getBody().clearInputs();
    }
    @Override protected void onTick(AIPlayer ai) {
        if (!ai.getBody().isUsable() || !input.world().equals(ai.getWorldId())) { fail("body/world changed"); return; }
        if (getElapsed() > ticks) { succeed(); return; }
        var player = ai.getPlayer();
        var eye = player.getEyeLocation(); var heading = input.heading();
        ai.getBody().lookAt(eye.getX() + heading.x() * 4, eye.getY() + heading.y() * 4, eye.getZ() + heading.z() * 4);
        ai.getBody().inputMove(input.forward(), input.strafe());
        ai.getBody().inputJump(input.jump());
        ai.getBody().inputSneak(input.sneak());
        // FakePlayer applies sprint flags directly: retain normal sprint eligibility, not a tactical food preference.
        ai.getBody().inputSprint(input.canSprint(player.getFoodLevel(), player.hasPotionEffect(PotionEffectType.BLINDNESS),
                player.isSprinting(), player.isInWater()));
        heldTicks++;
        boolean inWater = player.isInWater();
        boolean grounded = ai.getBody().isGrounded();
        if (!phaseObserved || inWater != lastInWater || grounded != lastGrounded) {
            ai.debug("MovementInput observed: inWater=" + inWater + " grounded=" + grounded + " elapsed=" + getElapsed()
                    + " tick=" + Integer.toUnsignedLong(org.bukkit.Bukkit.getCurrentTick()) + " input=" + input.identity());
            phaseObserved = true; lastInWater = inWater; lastGrounded = grounded;
        }
    }
    @Override protected void onEnd(AIPlayer ai) {
        ai.getBody().inputMove(0, 0); ai.getBody().inputJump(false);
        ai.getBody().inputSneak(false); ai.getBody().inputSprint(false);
        ai.debug("MovementInput released: heldTicks=" + heldTicks + " lastObservedInWater="
                + (phaseObserved ? Boolean.toString(lastInWater) : "UNKNOWN") + " lastObservedGrounded="
                + (phaseObserved ? Boolean.toString(lastGrounded) : "UNKNOWN"));
    }
    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.MOVE_INPUT; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Ticks(ticks); }
}
