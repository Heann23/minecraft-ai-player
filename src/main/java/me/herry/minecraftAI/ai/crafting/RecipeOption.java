package me.herry.minecraftAI.ai.crafting;

import java.util.List;
import java.util.Objects;

/** A value-only registry recipe. Each ingredient is an AND requirement with OR alternatives. */
public record RecipeOption(String id, String result, int resultAmount, boolean needsTable,
                           boolean smelting, List<Ingredient> ingredients) {
    public RecipeOption {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(result, "result");
        if (id.isBlank() || result.isBlank() || resultAmount < 1) throw new IllegalArgumentException("invalid recipe");
        ingredients = List.copyOf(ingredients);
        if (ingredients.isEmpty()) throw new IllegalArgumentException("recipe needs ingredients");
    }

    /** Exact choices cannot be resolved by a planner that knows material counts alone. */
    public boolean countPlannable() {
        return ingredients.stream().noneMatch(Ingredient::exact);
    }

    public record Ingredient(int amount, List<String> alternatives, boolean exact) {
        public Ingredient {
            if (amount < 1) throw new IllegalArgumentException("ingredient amount must be positive");
            alternatives = List.copyOf(alternatives);
            if (alternatives.isEmpty() || alternatives.stream().anyMatch(s -> s == null || s.isBlank())
                    || alternatives.stream().distinct().count() != alternatives.size()) {
                throw new IllegalArgumentException("ingredient needs distinct named alternatives");
            }
        }
    }
}
