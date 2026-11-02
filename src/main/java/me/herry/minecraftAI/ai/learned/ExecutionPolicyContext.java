package me.herry.minecraftAI.ai.learned;

import java.util.Arrays;

/** Observed executor facts. No continuation score, penalty or preferred action. */
public final class ExecutionPolicyContext {
    private ExecutionPolicyContext() {}
    public record Execution(boolean active, boolean running, boolean miningKnown, double progress,
                            int actionTicks, int aimTicks, String inputId) {}

    static void encode(double[] state, Execution execution, PolicyTaskMemory.View memory, java.util.UUID world) {
        if (state.length != PolicyFrame.STATE_SIZE) throw new IllegalArgumentException("execution context shape");
        Arrays.fill(state, 400, 432, 0);
        state[400] = execution.active() ? 1 : 0;
        state[401] = execution.running() ? 1 : 0;
        state[402] = execution.miningKnown() ? 1 : 0;
        if (execution.miningKnown()) {
            state[403] = Math.clamp(execution.progress(), 0, 1);
            state[404] = Math.clamp(execution.actionTicks() / 600.0, 0, 16);
            state[405] = Math.clamp(execution.aimTicks() / 15.0, 0, 16);
        }
        // Only this world's actual outcomes; cancellation is distinct from other execution failures.
        var history = memory.recentOutcomes();
        for (int i = 0; i < Math.min(4, history.size()); i++) {
            var outcome = history.get(history.size() - 1 - i);
            if (!world.equals(outcome.world())) continue;
            int offset = 416 + i * 4;
            state[offset] = 1;
            state[offset + 1] = outcome.status().equals("SUCCESS") ? 1 : 0;
            state[offset + 2] = outcome.reason().equals("cancelled") ? 1 : 0;
            state[offset + 3] = outcome.status().equals("FAILED") && !outcome.reason().equals("cancelled") ? 1 : 0;
        }
    }
}
