package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.observation.SurvivalObservation;
import net.minecraft.tags.FluidTags;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

/** Own current body facts. No movement choice, air threshold or predicted safe heading. */
final class MotionPolicyContext {
    private MotionPolicyContext() {}

    static void capture(Player player, SurvivalObservation.Body body, double[] state) {
        var handle = ((CraftPlayer) player).getHandle();
        var velocity = player.getVelocity();
        encode(state, player.getLocation().getYaw(), velocity.getX(), velocity.getZ(),
                new boolean[]{player.isSwimming(), handle.isEyeInFluid(FluidTags.WATER), player.isSprinting(),
                        player.isSneaking(), player.isGliding(), handle.onClimbable(), player.isInsideVehicle(),
                        player.getPose() == Pose.SNEAKING},
                new double[]{player.getWidth(), player.getHeight(), player.getEyeHeight()}, body.exhaustion());
    }

    static void encode(double[] state, double yaw, double vx, double vz, boolean[] flags, double[] dimensions, double exhaustion) {
        if (state.length != PolicyFrame.STATE_SIZE || flags.length != 8 || dimensions.length != 3
                || !Double.isFinite(yaw) || !Double.isFinite(vx) || !Double.isFinite(vz)
                || !Double.isFinite(exhaustion) || exhaustion < 0) throw new IllegalArgumentException("invalid motion context");
        state[192] = Math.sin(Math.toRadians(yaw)); state[193] = Math.cos(Math.toRadians(yaw));
        state[194] = Math.clamp(vx / 4, -16, 16); state[195] = Math.clamp(vz / 4, -16, 16);
        for (int i = 0; i < flags.length; i++) state[196 + i] = flags[i] ? 1 : 0;
        for (int i = 0; i < dimensions.length; i++) {
            if (!Double.isFinite(dimensions[i]) || dimensions[i] < 0) throw new IllegalArgumentException("invalid body dimensions");
            state[204 + i] = Math.min(16, dimensions[i] / (i == 0 ? 2 : 3));
        }
        state[207] = Math.min(16, exhaustion / 4);
    }
}
