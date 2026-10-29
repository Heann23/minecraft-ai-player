package me.herry.minecraftAI.ai.learned;

import java.util.HashSet;
import java.util.Set;

/** Confirmed game progress only. No action preference, task ordering or invented advancement completion. */
public final class AdvancementRewards {
    private final Set<String> observed = new HashSet<>();

    public double observe(String key, boolean completed, boolean displayed, boolean learningTransitionActive) {
        if (!completed || !displayed) return 0; // Recipe unlocks and partial criteria are not completed progress.
        if (key == null || key.length() > 256 || !key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("invalid observed advancement key");
        if (observed.size() >= 4096 || !observed.add(key)) return 0;
        // Observe even while inference/idle, preventing retroactive credit after learning is enabled.
        return learningTransitionActive ? 1 : 0;
    }
}
