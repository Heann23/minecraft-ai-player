package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.perception.Perception;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.primitive.runtime.PrimitiveAdapter;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Additional raw observations, never an escape direction, food preference, danger score or Teacher decision. */
final class LocalPolicyContext {
    private LocalPolicyContext() {}

    static void capture(AIPlayer ai, double[] state) {
        var feet = ai.getPosition();
        var points = cells(feet);
        var context = PrimitiveAdapter.inspect(ai, points);
        int known = 0;
        for (int cell = 0; cell < context.blocks().size(); cell++) {
            var observed = context.blocks().get(cell);
            Material material = Material.matchMaterial(observed.material());
            if (!observed.loaded() || !observed.visible() || material == null) continue;
            boolean water = material == Material.WATER;
            var block = ai.getPlayer().getWorld().getBlockAt(observed.point().x(), observed.point().y(), observed.point().z());
            if (block.getBlockData() instanceof Waterlogged wet && wet.isWaterlogged()) water = true;
            encodeCell(state, cell, true, material.isSolid(), water, material == Material.LAVA);
            known++;
        }
        state[125] = known / 12.0;
        var player = ai.getPlayer();
        var at = player.getLocation();
        var stamp = ai.getPerception().getEntityScanStamp();
        long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        if (fresh(stamp, player.getWorld().getUID(), now, at.getX(), at.getY(), at.getZ())) {
            boolean hostiles = entities(ai, ai.getPerception().getHostiles(), state, 113);
            boolean animals = entities(ai, ai.getPerception().getAnimals(), state, 119);
            state[112] = hostiles && animals && stamp.range() >= 32 ? 1 : 0;
        }
        state[126] = player.getNoDamageTicks() / (double) Math.max(1, player.getMaximumNoDamageTicks());
        var velocity = player.getVelocity();
        state[127] = Math.hypot(velocity.getX(), velocity.getZ()) / 2;
    }

    static List<BlockPoint> cells(BlockPoint feet) {
        var result = new ArrayList<BlockPoint>();
        for (int[] direction : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
            for (int y = -1; y <= 1; y++) result.add(feet.offset(direction[0], y, direction[1]));
        return List.copyOf(result);
    }

    static void encodeCell(double[] state, int cell, boolean known, boolean solid, boolean water, boolean lava) {
        if (cell < 0 || cell >= 12 || state.length != PolicyFrame.STATE_SIZE) throw new IllegalArgumentException("invalid context cell");
        int offset = 64 + cell * 4;
        state[offset] = known ? 1 : 0;
        state[offset + 1] = known && solid ? 1 : 0;
        state[offset + 2] = known && water ? 1 : 0;
        state[offset + 3] = known && lava ? 1 : 0;
    }

    static boolean fresh(Perception.EntityScanStamp stamp, UUID world, long now, double x, double y, double z) {
        return stamp != null && stamp.complete() && stamp.worldId().equals(world) && stamp.serverTick() == now
                && Double.compare(stamp.x(), x) == 0 && Double.compare(stamp.y(), y) == 0 && Double.compare(stamp.z(), z) == 0;
    }

    /** A zero count means observed absence only when the separate completeness flag is one. */
    private static boolean entities(AIPlayer ai, List<LivingEntity> observed, double[] state, int offset) {
        var player = ai.getPlayer();
        var at = player.getLocation();
        int checked = 0, count = 0;
        double nearest = Double.POSITIVE_INFINITY;
        boolean complete = true;
        for (var entity : observed) {
            if (checked++ >= 16 || ai.getWorkBudget().isExhausted()) { complete = false; break; }
            if (!entity.isValid() || entity.isDead() || !entity.getWorld().equals(player.getWorld())) { complete = false; continue; }
            var position = entity.getLocation();
            double distance = position.distanceSquared(at);
            if (distance > 32 * 32 || entity.isInvisible() || !PerceptionSystem.canSee(player, entity)) { complete = false; continue; }
            count++;
            if (distance < nearest) {
                nearest = distance;
                state[offset + 1] = (position.getX() - at.getX()) / 32;
                state[offset + 2] = (position.getY() - at.getY()) / 32;
                state[offset + 3] = (position.getZ() - at.getZ()) / 32;
                state[offset + 4] = Math.sqrt(distance) / 32;
                state[offset + 5] = entity.getHealth() / 40;
            }
        }
        state[offset] = count / 16.0;
        return complete;
    }
}
