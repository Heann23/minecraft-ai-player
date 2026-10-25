package me.herry.minecraftAI.ai.autonomy;

import java.util.Map;
import java.util.Objects;

/** Desired observable state; value is utility, independent of a route's execution cost. */
public record AutonomyGoal(String id, Map<String, Integer> required, double value) {
    public AutonomyGoal {
        id = PlanningState.checkedToken(id);
        required = PlanningState.checkedQuantities(required);
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("value must be finite and nonnegative");
    }

    public boolean achieved(PlanningState state) {
        return Objects.requireNonNull(state, "state").satisfies(required);
    }
}
