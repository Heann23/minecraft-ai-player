package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.AbstractAction;
import me.herry.minecraftAI.ai.primitive.*;
import org.bukkit.potion.PotionEffectType;

/** Holds only selected water keys for selected ticks in the existing Plan lane. Completion is not arrival/survival. */
final class WaterInputAction extends AbstractAction implements PrimitiveAction {
    private final WaterInput input;
    private final int ticks;
    private int heldTicks;
    private boolean phaseObserved;
    private boolean lastInWater;
    WaterInputAction(WaterInput input, int ticks) {
        super("WaterInput", duration(ticks) + 1);
        this.input = java.util.Objects.requireNonNull(input); this.ticks = ticks;
    }
    private static int duration(int ticks) {
        if (ticks < 1 || ticks > 600) throw new IllegalArgumentException("water input ticks must be 1..600");
        return ticks;
    }
    @Override protected void onStart(AIPlayer ai) {
        ai.getNavigation().stop(); ai.getBody().clearInputs();
    }
    @Override protected void onTick(AIPlayer ai) {
        if (!ai.getBody().isUsable() || !input.world().equals(ai.getWorldId())) { fail("body/world changed"); return; }
        if (getElapsed() > ticks) { succeed(); return; }
        var player = ai.getPlayer();
        var domain = WaterDomain.current(ai);
        if (domain != WaterDomain.Check.AVAILABLE) {
            fail(domain == WaterDomain.Check.UNKNOWN ? "water context unconfirmed" : "outside observed water area");
            return;
        }
        var eye = player.getEyeLocation(); var heading = input.heading();
        ai.getBody().lookAt(eye.getX() + heading.x() * 4, eye.getY() + heading.y() * 4, eye.getZ() + heading.z() * 4);
        ai.getBody().inputMove(input.forward(), 0.0F);
        ai.getBody().inputJump(input.vertical() > 0);
        ai.getBody().inputSneak(input.vertical() < 0);
        // FakePlayer applies sprint flags directly: retain normal sprint eligibility, not a tactical food preference.
        ai.getBody().inputSprint(input.canSprint(player.getFoodLevel(), player.hasPotionEffect(PotionEffectType.BLINDNESS),
                player.isSprinting(), player.isInWater()));
        heldTicks++;
        boolean inWater = player.isInWater();
        if (!phaseObserved || inWater != lastInWater) {
            ai.debug("WaterInput observed: inWater=" + inWater + " elapsed=" + getElapsed()
                    + " tick=" + Integer.toUnsignedLong(org.bukkit.Bukkit.getCurrentTick()) + " input=" + input.identity());
            phaseObserved = true; lastInWater = inWater;
        }
    }
    @Override protected void onEnd(AIPlayer ai) {
        ai.getBody().inputMove(0, 0); ai.getBody().inputJump(false);
        ai.getBody().inputSneak(false); ai.getBody().inputSprint(false);
        ai.debug("WaterInput released: heldTicks=" + heldTicks + " lastObservedInWater="
                + (phaseObserved ? Boolean.toString(lastInWater) : "UNKNOWN"));
    }
    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.SWIM; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Ticks(ticks); }
}
