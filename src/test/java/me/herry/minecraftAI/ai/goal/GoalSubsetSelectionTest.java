package me.herry.minecraftAI.ai.goal;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GoalSubsetSelectionTest {
    @Test void NormalProgressionScoresCannotLeakIntoTheSafetySubset() {
        var system = new GoalSystem();
        var state = new Situation();
        state.knowsDiamond = true; state.canMineDiamond = true;
        assertEquals(GoalType.IDLE, system.selectAmong(state, 0, Set.of(GoalType.ESCAPE_DANGER)).goal());
    }

    @Test void CriticalSurvivalStillBypassesCooldown() {
        var system = new GoalSystem();
        var state = new Situation(); state.inLava = true;
        system.cooldown(GoalType.ESCAPE_DANGER, 0, 100);
        assertEquals(GoalType.ESCAPE_DANGER, system.selectAmong(state, 10, Set.of(GoalType.ESCAPE_DANGER)).goal());
    }

    @Test void ExcludedGoalsDoNotPreemptAnExplicitlyRestrictedSelection() {
        var system = new GoalSystem();
        var state = new Situation(); state.inLava = true;
        assertEquals(GoalType.IDLE, system.selectAmong(state, 0, Set.of()).goal());
    }
}
