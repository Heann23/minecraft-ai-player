package me.herry.minecraftAI.ai.autonomy;

import java.util.Map;

/** Preconditions are checked before consumption; effects are positive quantity increments. */
public record ActionModel(String id, Map<String, Integer> preconditions, Map<String, Integer> consumed,
                          Map<String, Integer> effects, double cost) {
    public ActionModel {
        id = PlanningState.checkedToken(id);
        preconditions = PlanningState.checkedQuantities(preconditions);
        consumed = PlanningState.checkedQuantities(consumed);
        effects = PlanningState.checkedQuantities(effects);
        if (!Double.isFinite(cost) || cost <= 0) throw new IllegalArgumentException("cost must be finite and positive");
        for (var entry : consumed.entrySet()) {
            if (preconditions.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                throw new IllegalArgumentException("preconditions must cover consumed quantity: " + entry.getKey());
            }
        }
    }
}
