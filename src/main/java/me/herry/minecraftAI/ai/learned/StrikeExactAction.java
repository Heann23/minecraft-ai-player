package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.AbstractAction;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import org.bukkit.Bukkit;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

/** One normal attack with the current hand. No weapon selection, pursuit or repeat-until-kill. */
public final class StrikeExactAction extends AbstractAction {
    private final UUID world, target;
    public StrikeExactAction(UUID world, UUID target) { super("LearnedStrike", 1); this.world = world; this.target = target; }
    @Override protected void onStart(AIPlayer ai) {}
    @Override protected void onTick(AIPlayer ai) {
        if (!ai.getWorldId().equals(world) || !ai.getBody().isUsable()) { fail("world/body changed"); return; }
        var entity = Bukkit.getEntity(target);
        if (entity == null || !canReach(ai, entity)) {
            fail("target unavailable or outside melee reach"); return;
        }
        if (entity instanceof EnderCrystal crystal) {
            ai.getPlayer().attack(crystal);
            ai.getPlayer().swingMainHand();
            if (!crystal.isValid() || crystal.isDead()) succeed();
            else fail("end crystal survived melee input");
            return;
        }
        LivingEntity living = (LivingEntity) entity;
        double healthBefore = living.getHealth(), absorptionBefore = living.getAbsorptionAmount();
        Entity contact = living instanceof EnderDragon dragon ? ai.getBody().dragonHead(dragon) : living;
        ai.getPlayer().attack(contact); ai.getPlayer().swingMainHand();
        ai.debug("Learned strike observed: target=" + target + " healthBefore=" + healthBefore
                + " healthAfter=" + living.getHealth() + " absorptionBefore=" + absorptionBefore
                + " absorptionAfter=" + living.getAbsorptionAmount() + " deadAfter=" + living.isDead());
        // Normal attack input accepted, not a claim of hit, damage or kill. The next observed state carries the outcome.
        succeed();
    }

    public static boolean canReach(AIPlayer ai, Entity target) {
        if (!(target instanceof LivingEntity || target instanceof EnderCrystal) || target instanceof Player
                || !target.isValid() || target.isDead() || !target.getWorld().equals(ai.getPlayer().getWorld())) return false;
        if (target instanceof EnderDragon dragon && dragon.getPhase() == EnderDragon.Phase.DYING) return false;
        Entity contact = target instanceof EnderDragon dragon ? ai.getBody().dragonHead(dragon) : target;
        return distanceSquared(contact.getBoundingBox(), ai.getPlayer().getEyeLocation()) <= 9
                && PerceptionSystem.canSee(ai.getPlayer(), contact);
    }

    private static double distanceSquared(org.bukkit.util.BoundingBox box, org.bukkit.Location eye) {
        double dx = eye.getX() - Math.clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double dy = eye.getY() - Math.clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double dz = eye.getZ() - Math.clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        return dx * dx + dy * dy + dz * dz;
    }
}
