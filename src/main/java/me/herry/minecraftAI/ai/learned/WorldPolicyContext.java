package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.NearbyBlocks;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;

import java.util.Arrays;

/**
 * v10 factual world-context state observations (indices 432..447): dimension, altitude, light,
 * respawn presence, reachable workstations, and End boss/crystal geometry. No scores or action preferences.
 */
final class WorldPolicyContext {
    static final int OFFSET = 432;
    static final int WIDTH = 16;
    static final int TABLE_REACHABLE = 439;
    static final int FURNACE_REACHABLE = 440;

    private WorldPolicyContext() {}

    static void capture(AIPlayer ai, double[] state) {
        Player player = ai.getPlayer();
        World world = player.getWorld();
        World.Environment env = world.getEnvironment();
        Location eye = player.getEyeLocation();
        int blockLight = 0, skyLight = 0;
        if (eye.getBlockY() >= world.getMinHeight() && eye.getBlockY() < world.getMaxHeight()
                && world.isChunkLoaded(eye.getBlockX() >> 4, eye.getBlockZ() >> 4)) {
            var block = eye.getBlock();
            blockLight = block.getLightFromBlocks();
            skyLight = block.getLightFromSky();
        }
        Location respawn = player.getRespawnLocation();
        boolean hasRespawnInWorld = respawn != null && world.equals(respawn.getWorld());
        boolean tableReachable = false;
        boolean furnaceReachable = false;
        int ex = eye.getBlockX(), ey = eye.getBlockY(), ez = eye.getBlockZ();
        outer:
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    int bx = ex + dx, by = ey + dy, bz = ez + dz;
                    if (by < world.getMinHeight() || by >= world.getMaxHeight()
                            || !world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                    Material type = world.getBlockAt(bx, by, bz).getType();
                    if (!tableReachable && type == Material.CRAFTING_TABLE
                            && NearbyBlocks.canTouch(player, new me.herry.minecraftAI.ai.util.BlockPoint(bx, by, bz), 4.5)) {
                        tableReachable = true;
                    } else if (!furnaceReachable && type == Material.FURNACE
                            && NearbyBlocks.canTouch(player, new me.herry.minecraftAI.ai.util.BlockPoint(bx, by, bz), 4.5)) {
                        furnaceReachable = true;
                    }
                    if (tableReachable && furnaceReachable) break outer;
                }
            }
        }

        boolean dragonPresent = false;
        double dragonDx = 0, dragonDy = 0, dragonDz = 0, dragonHealthRatio = 0;
        if (env == World.Environment.THE_END) {
            for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
                if (!dragon.isValid() || dragon.isDead()) continue;
                Location head = ai.getBody().dragonHead(dragon).getLocation();
                dragonPresent = true;
                dragonDx = head.getX() - eye.getX();
                dragonDy = head.getY() - eye.getY();
                dragonDz = head.getZ() - eye.getZ();
                var attr = dragon.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = attr == null ? 200.0 : Math.max(1.0, attr.getValue());
                dragonHealthRatio = dragon.getHealth() / maxHealth;
                break;
            }
        }
        int visibleCrystals = 0;
        double nearestCrystal = 0;
        double nearestSq = Double.POSITIVE_INFINITY;
        for (EnderCrystal crystal : ai.getPerception().getEndCrystals()) {
            if (ai.getWorkBudget().isExhausted()) break;
            if (!crystal.isValid() || crystal.isDead() || !world.equals(crystal.getWorld())) continue;
            double distSq = crystal.getLocation().distanceSquared(eye);
            if (distSq > 96.0 * 96.0 || !PerceptionSystem.canSee(player, crystal)) continue;
            visibleCrystals++;
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearestCrystal = Math.sqrt(distSq);
            }
        }
        encode(state, env, ai.getPosition().y(), blockLight, skyLight, hasRespawnInWorld,
                tableReachable, furnaceReachable, dragonPresent, dragonDx, dragonDy, dragonDz,
                dragonHealthRatio, visibleCrystals, nearestCrystal);
    }

    static void encode(double[] state, World.Environment env, int feetY, int blockLight, int skyLight,
                       boolean hasRespawnInWorld, boolean tableReachable, boolean furnaceReachable,
                       boolean dragonPresent, double dragonDx, double dragonDy, double dragonDz,
                       double dragonHealthRatio, int visibleCrystals, double nearestCrystalDistance) {
        if (state.length != PolicyFrame.STATE_SIZE)
            throw new IllegalArgumentException("invalid world context state size");
        Arrays.fill(state, OFFSET, OFFSET + WIDTH, 0);
        state[432] = env == World.Environment.NORMAL ? 1 : 0;
        state[433] = env == World.Environment.NETHER ? 1 : 0;
        state[434] = env == World.Environment.THE_END ? 1 : 0;
        state[435] = Math.clamp((feetY + 64.0) / 384.0, 0, 1);
        state[436] = Math.clamp(blockLight / 15.0, 0, 1);
        state[437] = Math.clamp(skyLight / 15.0, 0, 1);
        state[438] = hasRespawnInWorld ? 1 : 0;
        state[TABLE_REACHABLE] = tableReachable ? 1 : 0;
        state[FURNACE_REACHABLE] = furnaceReachable ? 1 : 0;
        if (dragonPresent) {
            state[441] = 1;
            state[442] = Math.clamp(dragonDx / 128.0, -1, 1);
            state[443] = Math.clamp(dragonDy / 128.0, -1, 1);
            state[444] = Math.clamp(dragonDz / 128.0, -1, 1);
            state[445] = Math.clamp(dragonHealthRatio, 0, 1);
        }
        if (visibleCrystals > 0) {
            state[446] = Math.clamp(visibleCrystals / 12.0, 0, 1);
            state[447] = Math.clamp(nearestCrystalDistance / 96.0, 0, 1);
        }
    }
}
