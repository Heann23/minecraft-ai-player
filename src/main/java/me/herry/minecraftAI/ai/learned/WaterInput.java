package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import java.util.*;

/** Chosen keys and heading. No air/health threshold or destination/escape policy. */
record WaterInput(UUID world, double yaw, double pitch, int forward, int vertical, boolean sprint) {
    static final int CHOICES = 144;
    WaterInput {
        Objects.requireNonNull(world);
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch) || pitch < -90 || pitch > 90
                || forward < 0 || forward > 1 || vertical < -1 || vertical > 1)
            throw new IllegalArgumentException("invalid water input");
        yaw = ((yaw % 360) + 360) % 360;
    }
    PrimitiveTarget.Point heading() {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new PrimitiveTarget.Point(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }
    boolean canSprint(int food, boolean blind, boolean alreadySprinting) {
        return sprint && forward > 0 && food > 6 && (!blind || alreadySprinting);
    }
    String identity() { return "water:" + yaw + ":" + pitch + ":" + forward + ":" + vertical + ":" + sprint; }

    static List<WaterInput> choices(UUID world, double yaw, int cursor, int limit) {
        if (limit < 1 || limit > CHOICES) throw new IllegalArgumentException("invalid water candidate budget");
        List<WaterInput> result = new ArrayList<>(limit);
        // Coprime permutation spreads headings AND keys across a limited prefix, then rotates it.
        for (int i = 0; i < limit; i++) {
            int index = Math.floorMod(Math.floorMod(cursor + i, CHOICES) * 37, CHOICES);
            int yawIndex = index % 4; index /= 4;
            int pitchIndex = index % 3; index /= 3;
            int forward = index % 2; index /= 2;
            int vertical = index % 3 - 1; index /= 3;
            result.add(new WaterInput(world, yaw + new int[]{-90, 0, 90, 180}[yawIndex],
                    new int[]{-60, 0, 60}[pitchIndex], forward, vertical, index != 0));
        }
        return List.copyOf(result);
    }
}
