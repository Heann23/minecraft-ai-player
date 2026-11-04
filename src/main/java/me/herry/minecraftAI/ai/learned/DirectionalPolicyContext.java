package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.util.Vector;
import java.util.Arrays;
import java.util.ArrayList;

/** Eight yaw-offset sight rays at the current view pitch. No resource ranking or suggested movement. */
final class DirectionalPolicyContext {
    private static final double[] ANGLES = {-60, -30, -10, -2, 2, 10, 30, 60};
    private static final int RANGE = 16;
    private DirectionalPolicyContext() {}

    static PolicyFrame.SightObservation capture(AIPlayer ai, double[] state) {
        var player = ai.getPlayer();
        var eye = player.getEyeLocation();
        var world = player.getWorld();
        long tick = Integer.toUnsignedLong(org.bukkit.Bukkit.getCurrentTick());
        var rays = new ArrayList<PolicyFrame.SightRay>(8);
        for (int ray = 0; ray < ANGLES.length; ray++) {
            if (ai.getWorkBudget().isExhausted()) {
                rays.add(new PolicyFrame.SightRay(ray, "BUDGET_UNKNOWN", -1, null));
                continue;
            }
            double radians = Math.toRadians(eye.getYaw() + ANGLES[ray]);
            double pitch = Math.toRadians(eye.getPitch());
            var direction = new Vector(-Math.sin(radians) * Math.cos(pitch), -Math.sin(pitch),
                    Math.cos(radians) * Math.cos(pitch));
            boolean loaded = true;
            boolean budgetUnknown = false;
            // Never ask the ray tracer to load an unseen chunk.
            int startX = eye.getBlockX() >> 4, startZ = eye.getBlockZ() >> 4;
            int endX = ((int) Math.floor(eye.getX() + direction.getX() * RANGE)) >> 4;
            int endZ = ((int) Math.floor(eye.getZ() + direction.getZ() * RANGE)) >> 4;
            // The full bounding rectangle also covers tiny diagonal intersections between sample points.
            for (int x = Math.min(startX, endX); x <= Math.max(startX, endX) && loaded; x++)
                for (int z = Math.min(startZ, endZ); z <= Math.max(startZ, endZ); z++)
                    if (ai.getWorkBudget().isExhausted()) { loaded = false; budgetUnknown = true; break; }
                    else if (!world.isChunkLoaded(x, z)) { loaded = false; break; }
            if (!loaded) {
                rays.add(new PolicyFrame.SightRay(ray, budgetUnknown ? "BUDGET_UNKNOWN" : "UNLOADED_UNKNOWN", -1, null));
                continue;
            }
            var hit = world.rayTraceBlocks(eye, direction, RANGE, FluidCollisionMode.ALWAYS, false);
            var block = hit == null ? null : hit.getHitBlock();
            if (block == null) {
                encode(state, ray, true, null, RANGE, false);
                rays.add(new PolicyFrame.SightRay(ray, "CLEAR", RANGE, null));
            } else {
                double distance = hit.getHitPosition().distance(eye.toVector());
                String material = block.getType().name();
                encode(state, ray, true, material, distance, block.getType().isSolid());
                rays.add(new PolicyFrame.SightRay(ray, "HIT", distance,
                        new PolicyFrame.BlockEvidence(material, world.getUID(), block.getX(), block.getY(), block.getZ(), tick)));
            }
        }
        return new PolicyFrame.SightObservation(world.getUID(), tick, eye.getX(), eye.getY(), eye.getZ(),
                eye.getYaw(), eye.getPitch(), rays);
    }

    static void encode(double[] state, int ray, boolean known, String material, double distance, boolean solid) {
        if (state.length != PolicyFrame.STATE_SIZE || ray < 0 || ray >= 8 || !Double.isFinite(distance)
                || distance < 0 || distance > RANGE + 1e-9
                || material != null && !material.matches("[A-Z][A-Z0-9_]{0,127}"))
            throw new IllegalArgumentException("invalid directional observation");
        int offset = 240 + ray * 20;
        Arrays.fill(state, offset, offset + 20, 0);
        if (!known) return;
        state[offset] = 1;
        state[offset + 1] = material == null ? 0 : 1;
        state[offset + 2] = Math.min(1, distance / RANGE);
        state[offset + 3] = material != null && solid ? 1 : 0;
        if (material != null) state[offset + 4 + Math.floorMod(material.hashCode(), 16)] = 1;
    }
}
