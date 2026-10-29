package me.herry.minecraftAI.ai.learned;

/** v5 state extension. Goal identity is bounded/hashing, not a semantic generalization guarantee. */
final class TaskPolicyContext {
    private TaskPolicyContext() {}
    static void encode(double[] state, PolicyTaskMemory.View view, long tick, java.util.UUID world) {
        if (state.length != PolicyFrame.STATE_SIZE || tick < 0) throw new IllegalArgumentException("invalid task context");
        if (view.objective() != null) {
            state[208] = 1; state[209] = view.worldMatches() ? 1 : 0;
            state[210] = Math.min(16, view.objective().goal().count() / 64.0);
            state[211] = Math.min(16, view.owned() / 64.0);
            state[212] = Math.min(16, view.remaining() / 64.0);
            state[213] = view.currentlyAchieved() ? 1 : 0;
            state[214] = Math.clamp((tick - view.objective().startedTick()) / 24000.0, 0, 16);
            state[215] = 1; // Exact current-ownership AcquireGoal, not a crafting-event goal.
            state[216 + Math.floorMod(view.objective().goal().item().hashCode(), 16)] = 1;
        }
        var history = view.recentOutcomes();
        for (int i = 0; i < Math.min(4, history.size()); i++) {
            var outcome = history.get(history.size() - 1 - i);
            // Another world's outcome never masquerades as current-world working memory.
            if (!outcome.world().equals(world)) continue;
            String kind = outcome.action().split(":", 2)[0];
            state[232 + i * 2] = (Math.floorMod(kind.hashCode(), 127) + 1) / 128.0;
            state[233 + i * 2] = outcome.status().equals("SUCCESS") ? 1 : -1;
        }
    }
}
