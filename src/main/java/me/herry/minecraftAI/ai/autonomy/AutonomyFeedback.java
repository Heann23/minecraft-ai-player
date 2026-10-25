package me.herry.minecraftAI.ai.autonomy;

/** Actual observations decide attainment. Search predictions alone are never evidence of success. */
public final class AutonomyFeedback {
    public enum Outcome { ACHIEVED, PROGRESSED, NO_PROGRESS }

    private AutonomyFeedback() {}

    public static Outcome assess(AutonomyGoal objective, ActionModel action, PlanningState before, PlanningState after) {
        if (objective.achieved(after)) return Outcome.ACHIEVED;
        for (String token : action.effects().keySet()) {
            if (after.count(token) > before.count(token)) return Outcome.PROGRESSED;
        }
        if (action.effects().keySet().stream().anyMatch(token -> token.startsWith("pending:"))
                && after.quantities().keySet().stream().anyMatch(token -> token.startsWith("pending:")
                && after.count(token) > before.count(token))) return Outcome.PROGRESSED;
        if (before.count("remaining:shelter") > after.count("remaining:shelter")
                && before.count("remaining:shelter") > 0) return Outcome.PROGRESSED;
        if (action.effects().containsKey("world:portal")
                && after.count("stock:portal-frame") > before.count("stock:portal-frame")) return Outcome.PROGRESSED;
        return Outcome.NO_PROGRESS;
    }
}
