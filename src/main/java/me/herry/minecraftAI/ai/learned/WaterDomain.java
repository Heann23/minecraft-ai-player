package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.runtime.PrimitiveAdapter;
import org.bukkit.block.data.Waterlogged;

/** Observed local input domain, not a direction, water-entry goal, rescue rule or prediction of safety. */
final class WaterDomain {
    enum Check { AVAILABLE, UNAVAILABLE, UNKNOWN }
    private WaterDomain() {}

    static boolean observed(double[] state) {
        shape(state);
        if (state[7] > 0) return true;
        for (int cell = 0; cell < 12; cell++) {
            int at = 64 + cell * 4;
            if (state[at] > 0 && state[at + 2] > 0) return true;
        }
        return false;
    }
    static boolean knownDry(double[] state) {
        shape(state);
        if (observed(state)) return false;
        for (int cell = 0; cell < 12; cell++) if (state[64 + cell * 4] <= 0) return false;
        return true;
    }
    private static void shape(double[] state) {
        if (state.length != PolicyFrame.STATE_SIZE) throw new IllegalArgumentException("water context shape mismatch");
    }

    static Check current(AIPlayer ai) {
        if (ai.getPlayer().isInWater()) return Check.AVAILABLE;
        boolean unknown = false;
        for (var point : LocalPolicyContext.cells(ai.getPosition())) {
            var seen = PrimitiveAdapter.inspectLocalBlock(ai, point);
            if (!seen.loaded() || !seen.visible()) { unknown = true; continue; }
            if (seen.material().equals("WATER")) return Check.AVAILABLE;
            if (ai.getWorkBudget().isExhausted()) { unknown = true; continue; }
            var data = ai.getPlayer().getWorld().getBlockAt(point.x(), point.y(), point.z()).getBlockData();
            if (data instanceof Waterlogged wet && wet.isWaterlogged()) return Check.AVAILABLE;
        }
        return unknown ? Check.UNKNOWN : Check.UNAVAILABLE;
    }
}
