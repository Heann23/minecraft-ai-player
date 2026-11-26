package me.herry.minecraftAI.ai.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakBlockActionTest {
    @Test void bareHandProgressReachesTheThresholdOnTheSixtiethTick() {
        float speed = 1.0F / 60;
        double progress = 0;
        for (int tick = 0; tick < 59; tick++) progress += speed;

        assertFalse(BreakBlockAction.completedMining(progress));
        assertTrue(BreakBlockAction.completedMining(progress + speed));
    }

    @Test void completionRoundsTheTotalOnceWithoutAddingAProgressTolerance() {
        float speed = 0.02F;
        assertFalse(BreakBlockAction.completedMining(49 * (double) speed));
        assertTrue(BreakBlockAction.completedMining(50 * (double) speed));
    }
}
