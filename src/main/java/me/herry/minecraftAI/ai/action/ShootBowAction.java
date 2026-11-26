package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/** Aim at the selected target and perform one vanilla bow draw and release. */
public final class ShootBowAction extends AbstractAction {
    public static final int ACTION_TICKS = 35;
    public static final double RANGE = 32;
    private static final int FULL_DRAW_TICKS = 20;
    private final PrimitiveCommand.ShootBow shot;
    private int heldSlot;
    private ItemStack bow;
    private boolean drawing;
    private Vector previousCenter;
    private Vector targetMotion = new Vector();

    public ShootBowAction(PrimitiveCommand.ShootBow shot) {
        super("ShootBow", ACTION_TICKS);
        this.shot = shot;
    }

    public static String check(AIPlayer ai, PrimitiveCommand.ShootBow shot) {
        if (!shot.world().equals(ai.getWorldId())) return "bow target world changed";
        if (!ai.getBody().canShootBow()) return "main-hand bow and usable ammunition required";
        return visibleTarget(ai, shot) == null ? "target unavailable for a visible bow shot" : "";
    }

    private static Entity visibleTarget(AIPlayer ai, PrimitiveCommand.ShootBow shot) {
        Entity target = Bukkit.getEntity(shot.entityId());
        if (target == null || !target.isValid() || target.isDead() || !target.getWorld().equals(ai.getPlayer().getWorld())) return null;
        Entity contact;
        if (target instanceof EnderDragon dragon) {
            switch (dragon.getPhase()) {
                case BREATH_ATTACK, ROAR_BEFORE_ATTACK, SEARCH_FOR_BREATH_ATTACK_TARGET, DYING -> { return null; }
                default -> contact = ai.getBody().dragonHead(dragon);
            }
        } else if (target instanceof EnderCrystal || target instanceof org.bukkit.entity.Enemy) contact = target;
        else return null;
        var eye = ai.getPlayer().getEyeLocation();
        return contact.getBoundingBox().getCenter().distanceSquared(eye.toVector()) <= RANGE * RANGE
                && PerceptionSystem.canSee(ai.getPlayer(), contact) ? contact : null;
    }

    @Override protected void onStart(AIPlayer ai) {
        String blocked = check(ai, shot);
        if (!blocked.isEmpty()) { fail(blocked); return; }
        var player = ai.getPlayer();
        if (player.hasActiveItem()) { fail("already using an item"); return; }
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
        heldSlot = player.getInventory().getHeldItemSlot();
        bow = player.getInventory().getItemInMainHand().clone();
        drawing = ai.getBody().useItem() && ownsDraw(ai);
        if (!drawing) fail("vanilla bow draw denied");
    }

    @Override protected void onTick(AIPlayer ai) {
        if (!shot.world().equals(ai.getWorldId()) || !ai.getBody().isUsable() || !ownsDraw(ai)) {
            fail("bow draw interrupted"); return;
        }
        var target = visibleTarget(ai, shot);
        if (target == null) { fail("selected target no longer available for a visible bow shot"); return; }
        Vector center = target.getBoundingBox().getCenter();
        Vector aim = center.clone();
        if (target instanceof org.bukkit.entity.EnderDragonPart) {
            if (previousCenter != null) {
                Vector step = center.clone().subtract(previousCenter);
                if (step.lengthSquared() <= 4) targetMotion.multiply(0.5).add(step.multiply(0.5));
                else targetMotion.zero();
            }
            previousCenter = center;
            double leadTicks = aim.distance(ai.getPlayer().getEyeLocation().toVector()) / 3;
            aim.add(targetMotion.clone().multiply(leadTicks));
        }
        // Full-power arrows begin at roughly three blocks per tick and fall by 0.05 per tick.
        double travelTicks = aim.distance(ai.getPlayer().getEyeLocation().toVector()) / 3;
        aim.setY(aim.getY() + 0.1 + 0.025 * travelTicks * travelTicks);
        var body = ai.getBody();
        body.lookAt(aim.getX(), aim.getY(), aim.getZ());
        if (ai.getPlayer().getActiveItemUsedTime() < FULL_DRAW_TICKS
                || !body.isFacing(aim.getX(), aim.getY(), aim.getZ(), 1.0f)) return;
        if (!body.releaseBow()) { fail("bow release denied"); return; }
        drawing = false;
        // The later world observation, including a possible miss, carries the projectile outcome.
        succeed();
    }

    private boolean ownsDraw(AIPlayer ai) {
        var player = ai.getPlayer();
        return player.hasActiveItem() && player.getActiveItemHand() == EquipmentSlot.HAND
                && player.getInventory().getHeldItemSlot() == heldSlot
                && bow.isSimilar(player.getInventory().getItemInMainHand()) && bow.isSimilar(player.getActiveItem());
    }

    @Override protected void onEnd(AIPlayer ai) {
        if (drawing && ownsDraw(ai)) ai.getPlayer().clearActiveItem();
        ai.getBody().clearInputs();
    }
}
