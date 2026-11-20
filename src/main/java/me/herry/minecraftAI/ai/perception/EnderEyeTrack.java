package me.herry.minecraftAI.ai.perception;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EnderSignal;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Short-lived memory of actual visible samples from this player's last throw. */
public final class EnderEyeTrack {
    public static final int MEMORY_TICKS = 240;
    private static final double RANGE = 32;

    public record Observation(double x, double y, double z, double dx, double dy, double dz,
                              int age, boolean visible) {}

    private UUID world;
    private UUID eye;
    private Location launch;
    private Location last;
    private Location furthest;
    private int thrownTick;
    private int lastSeenTick;

    void begin(EnderSignal signal) {
        world = signal.getWorld().getUID();
        eye = signal.getUniqueId();
        launch = signal.getLocation();
        last = null;
        furthest = null;
        thrownTick = Bukkit.getCurrentTick();
    }

    Observation observe(Player player) {
        if (eye == null || !player.getWorld().getUID().equals(world)) return null;
        int now = Bukkit.getCurrentTick();
        if (Integer.toUnsignedLong(now - thrownTick) > MEMORY_TICKS) {
            clear();
            return null;
        }
        boolean visible = false;
        if (Bukkit.getEntity(eye) instanceof EnderSignal signal && signal.isValid() && signal.getWorld().equals(player.getWorld())) {
            Location at = signal.getLocation();
            if (at.distanceSquared(player.getEyeLocation()) <= RANGE * RANGE && PerceptionSystem.canSee(player, signal)) {
                visible = true;
                last = at;
                lastSeenTick = now;
                if (furthest == null || at.distanceSquared(launch) > furthest.distanceSquared(launch)) furthest = at;
            }
        }
        if (last == null) return null;
        // The eye can slow down or double back. Retain its furthest observed displacement from the release.
        return new Observation(last.getX(), last.getY(), last.getZ(), furthest.getX() - launch.getX(),
                furthest.getY() - launch.getY(), furthest.getZ() - launch.getZ(), now - lastSeenTick, visible);
    }

    void clear() {
        world = null;
        eye = null;
        launch = null;
        last = null;
        furthest = null;
    }
}
