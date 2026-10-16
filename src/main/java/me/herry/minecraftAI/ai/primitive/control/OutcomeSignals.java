package me.herry.minecraftAI.ai.primitive.control;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Observed components with no teacher-agreement score or fixed reward weighting. */
public record OutcomeSignals(int elapsedTicks, double healthLost, boolean died,
                             Map<String, Integer> itemDelta, boolean objectiveReached) {
    public OutcomeSignals {
        if (elapsedTicks < 0) throw new IllegalArgumentException("elapsedTicks must be nonnegative");
        ContractValues.finiteRange(healthLost, 0, 1024, "healthLost");
        itemDelta = Map.copyOf(Objects.requireNonNull(itemDelta, "itemDelta"));
        if (itemDelta.size() > 82) throw new IllegalArgumentException("itemDelta has at most 82 materials");
        for (var entry : itemDelta.entrySet()) {
            ContractValues.material(entry.getKey());
            ContractValues.range(entry.getValue(), -41 * 1024, 41 * 1024, "item delta");
            if (entry.getValue() == 0) throw new IllegalArgumentException("itemDelta omits unchanged materials");
        }
    }

    public static OutcomeSignals between(PrimitiveSnapshot before, PrimitiveSnapshot after,
                                         boolean objectiveReached) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        long elapsed = Math.subtractExact(after.tick(), before.tick());
        if (elapsed < 0) throw new IllegalArgumentException("after snapshot precedes before snapshot");
        var deltas = new HashMap<String, Integer>();
        for (var item : before.inventory()) deltas.merge(item.material(), -item.amount(), Math::addExact);
        for (var item : after.inventory()) deltas.merge(item.material(), item.amount(), Math::addExact);
        deltas.values().removeIf(delta -> delta == 0);
        return new OutcomeSignals(Math.toIntExact(elapsed), Math.max(0, before.health() - after.health()),
                before.alive() && !after.alive(), deltas, objectiveReached);
    }
}
