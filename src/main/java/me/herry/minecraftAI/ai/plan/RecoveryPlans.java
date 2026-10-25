package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.UseObservedItemAction;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.observation.RiskObservation;
import me.herry.minecraftAI.ai.survival.RecoveryChoices;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Bukkit;

import java.util.List;

/** Conservative stationary recovery. Ongoing environmental escape retains its own higher-priority path. */
public final class RecoveryPlans {
    private RecoveryPlans() {}

    public static boolean canPause(AIPlayer ai) {
        if (!ai.getBody().isUsable() || ai.getPlayer().isSleeping() || ai.getPlayer().isInsideVehicle()
                || ai.getWorkBudget().isExhausted()) return false;
        var risk = ai.riskObservation();
        var e = risk.exposure();
        if (e.inLava() || e.fireTicks() > 0 || e.inWater() || e.freezeTicks() > 0 || !e.grounded()
                || e.fallDistance() > 0 || e.suffocation() != RiskObservation.Presence.ABSENT) return false;
        var world = ai.getPlayer().getWorld();
        var feet = ai.getPosition();
        for (var cell : List.of(feet, feet.offset(0, -1, 0))) {
            if (!Positions.isLoaded(world, cell) || cell.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight()
                    || BukkitTerrainView.classify(Positions.block(world, cell).getType()) == BlockClass.DANGER) return false;
        }
        var stamp = ai.getPerception().getEntityScanStamp();
        long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        var at = ai.getPlayer().getLocation();
        // A short, explicitly bounded runtime lease does not relabel RiskObservation.UNKNOWN as fresh ABSENT.
        if (stamp == null || !stamp.complete() || !stamp.worldId().equals(ai.getWorldId()) || now < stamp.serverTick()
                || now - stamp.serverTick() > Math.min(20, Math.max(1, ai.getConfig().updateInterval))
                || stamp.range() < ai.getConfig().fleeDistance
                || Math.abs(stamp.x() - at.getX()) > 1 || Math.abs(stamp.y() - at.getY()) > 1
                || Math.abs(stamp.z() - at.getZ()) > 1) return false;
        if (risk.damageEvents().stream().anyMatch(event -> event.damage().eventFinalDamage() > 0
                && now - event.damage().serverTick() < 10)) return false;
        int inspected = 0;
        boolean nearby = false;
        for (var hostile : ai.getPerception().getHostiles()) {
            if (++inspected > 32 || ai.getWorkBudget().isExhausted()) return false;
            if (!hostile.isValid() || hostile.isDead() || !hostile.getWorld().equals(world)) continue;
            if (hostile.getLocation().distanceSquared(at) <= ai.getConfig().fleeDistance * ai.getConfig().fleeDistance) nearby = true;
        }
        return !nearby || RefugePlans.isHidden(ai);
    }

    public static boolean available(AIPlayer ai) {
        if (!canPause(ai)) return false;
        return !RecoveryChoices.rank(ai.survivalObservation(), true).isEmpty();
    }

    public static List<Action> choose(AIPlayer ai) {
        if (!canPause(ai)) return List.of();
        var observed = ai.survivalObservation();
        var choices = RecoveryChoices.rank(observed, true);
        if (choices.isEmpty()) return List.of();
        var chosen = choices.getFirst();
        ai.debug("Recovery slot=" + chosen.item().slot() + ": " + chosen.reason()
                + (observed.inventoryComplete() ? "" : "; inventory comparison incomplete"));
        return List.of(new UseObservedItemAction(observed.worldId(), observed.serverTick(), chosen.item()));
    }
}
