package me.herry.minecraftAI.ai.autonomy;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable quantities used by symbolic search, not an assertion about the live world. */
public record PlanningState(Map<String, Integer> quantities) {
    public PlanningState {
        quantities = checkedQuantities(quantities);
    }

    public int count(String token) {
        return quantities.getOrDefault(checkedToken(token), 0);
    }

    public boolean satisfies(Map<String, Integer> required) {
        Objects.requireNonNull(required, "required");
        for (var entry : required.entrySet()) {
            String token = checkedToken(entry.getKey());
            Integer amount = Objects.requireNonNull(entry.getValue(), "quantity");
            if (amount < 0) throw new IllegalArgumentException("quantity must be nonnegative");
            if (count(token) < amount) return false;
        }
        return true;
    }

    public PlanningState apply(ActionModel action) {
        Objects.requireNonNull(action, "action");
        if (!satisfies(action.preconditions())) throw new IllegalArgumentException("preconditions not satisfied");
        Map<String, Integer> next = new TreeMap<>(quantities);
        action.consumed().forEach((token, amount) -> next.put(token, next.getOrDefault(token, 0) - amount));
        action.effects().forEach((token, amount) -> next.merge(token, amount, Math::addExact));
        return new PlanningState(next);
    }

    static String checkedToken(String token) {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) throw new IllegalArgumentException("token must be nonblank");
        return token;
    }

    static Map<String, Integer> checkedQuantities(Map<String, Integer> values) {
        Objects.requireNonNull(values, "quantities");
        Map<String, Integer> copy = new TreeMap<>();
        for (var entry : values.entrySet()) {
            String token = checkedToken(entry.getKey());
            int quantity = Objects.requireNonNull(entry.getValue(), "quantity");
            if (quantity < 0) throw new IllegalArgumentException("quantity must be nonnegative");
            if (quantity > 0) copy.put(token, quantity);
        }
        return Collections.unmodifiableMap(copy);
    }
}
