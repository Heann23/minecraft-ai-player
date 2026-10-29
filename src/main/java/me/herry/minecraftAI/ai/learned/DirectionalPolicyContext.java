package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.util.Vector;
import java.util.Arrays;

/** Eight horizontal sight rays in the current heading. No resource ranking or suggested movement. */
final class DirectionalPolicyContext {
    private static final double[] ANGLES = {-60, -30, -10, -2, 2, 10, 30, 60};
    private static final int RANGE = 16;
    private DirectionalPolicyContext() {}

    static void capture(AIPlayer ai, double[] state) {
        var player = ai.getPlayer();
        var eye = player.getEyeLocation();
        var world = player.getWorld();
        for (int ray = 0; ray < ANGLES.length; ray++) {
            if (ai.getWorkBudget().isExhausted()) break;
            double radians = Math.toRadians(eye.getYaw() + ANGLES[ray]);
            var direction = new Vector(-Math.sin(radians), 0, Math.cos(radians));
            boolean loaded = true;
            // Never ask the ray tracer to load an unseen chunk.
            for (int step = 0; step <= RANGE; step++) {
                int x = (int) Math.floor(eye.getX() + direction.getX() * step);
                int z = (int) Math.floor(eye.getZ() + direction.getZ() * step);
                if (ai.getWorkBudget().isExhausted() || !world.isChunkLoaded(x >> 4, z >> 4)) { loaded = false; break; }
            }
            if (!loaded) continue;
            var hit = world.rayTraceBlocks(eye, direction, RANGE, FluidCollisionMode.ALWAYS, false);
            var block = hit == null ? null : hit.getHitBlock();
            if (block == null) encode(state, ray, true, null, RANGE, false);
            else encode(state, ray, true, block.getType().name(),
                    hit.getHitPosition().distance(eye.toVector()), block.getType().isSolid());
        }
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
