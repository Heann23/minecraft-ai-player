package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.crafting.RecipeOption;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import org.bukkit.Material;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure factual candidate-material, recipe-structure and objective-relation action observations (indices 256..287).
 * Never encodes hand-written utilities, progression order or item preferences. Objective-relation bits are masked to 0
 * when objective input is masked, matching TaskPolicyContext.
 */
final class CandidateActionContext {
    static final int OFFSET = 256;
    static final int HAS_MATERIAL = 272;
    static final int OWNED_MATERIAL = 273;
    static final int UNOWNED_MATERIAL = 274;
    static final int OBJECTIVE_DIRECT_MATCH = 275;
    static final int OBJECTIVE_INGREDIENT_MATCH = 276;
    static final int CONSUMES_OBJECTIVE_ITEM = 277;
    static final int RECIPE_NEEDS_TABLE = 278;
    static final int RECIPE_INGREDIENT_UNITS = 279;
    static final int RECIPE_INGREDIENT_GROUPS = 280;

    record Context(Map<String, Integer> stock, PolicyTaskMemory.View task, boolean objectiveVisible,
                   Set<String> objectiveIngredients) {
        Context {
            stock = stock == null ? Map.of() : Map.copyOf(stock);
            objectiveIngredients = objectiveIngredients == null ? Set.of() : Set.copyOf(objectiveIngredients);
        }
    }

    private CandidateActionContext() {}

    static Context capture(CraftingSystem crafting, Map<String, Integer> stock,
                           PolicyTaskMemory.View task, boolean objectiveVisible) {
        return new Context(stock, task, objectiveVisible, objectiveIngredients(crafting, task, objectiveVisible));
    }

    static Set<String> objectiveIngredients(CraftingSystem crafting, PolicyTaskMemory.View task, boolean objectiveVisible) {
        if (!objectiveVisible || crafting == null || task == null || task.objective() == null
                || !task.worldMatches() || task.currentlyAchieved()) return Set.of();
        Material target = Material.matchMaterial(task.objective().goal().item());
        if (target == null) return Set.of();
        Set<String> ingredients = new HashSet<>();
        for (RecipeOption option : crafting.recipeOptions(target)) {
            if (!option.countPlannable() || option.smelting()) continue;
            for (RecipeOption.Ingredient ingredient : option.ingredients()) ingredients.addAll(ingredient.alternatives());
        }
        return ingredients.isEmpty() ? Set.of() : Collections.unmodifiableSet(ingredients);
    }

    static void encode(double[] features, PrimitiveType type, String directMaterial, RecipeOption recipe, Context context) {
        if (features.length != PolicyFrame.ACTION_SIZE) throw new IllegalArgumentException("candidate action context shape");
        boolean hasMaterial = directMaterial != null && !directMaterial.isBlank();
        if (hasMaterial) {
            features[OFFSET + Math.floorMod(directMaterial.hashCode(), 16)] = 1;
            features[HAS_MATERIAL] = 1;
            int owned = Math.max(0, context.stock().getOrDefault(directMaterial, 0));
            features[OWNED_MATERIAL] = Math.min(16, owned / 64.0);
            features[UNOWNED_MATERIAL] = owned == 0 ? 1 : 0;
        }
        var task = context.task();
        if (context.objectiveVisible() && task != null && task.objective() != null && task.worldMatches()) {
            String goalItem = task.objective().goal().item();
            boolean acquires = type == PrimitiveType.CRAFT_ITEM || type == PrimitiveType.BREAK_BLOCK;
            if (!task.currentlyAchieved() && acquires && hasMaterial) {
                if (goalItem.equals(directMaterial)) features[OBJECTIVE_DIRECT_MATCH] = 1;
                if (context.objectiveIngredients().contains(directMaterial)) features[OBJECTIVE_INGREDIENT_MATCH] = 1;
            }
            if (type == PrimitiveType.PLACE_BLOCK && hasMaterial && goalItem.equals(directMaterial)) {
                features[CONSUMES_OBJECTIVE_ITEM] = 1;
            } else if (type == PrimitiveType.CRAFT_ITEM && recipe != null
                    && recipe.ingredients().stream().anyMatch(ingredient -> ingredient.alternatives().contains(goalItem))) {
                features[CONSUMES_OBJECTIVE_ITEM] = 1;
            }
        }
        if (recipe != null) {
            features[RECIPE_NEEDS_TABLE] = recipe.needsTable() ? 1 : 0;
            int totalUnits = recipe.ingredients().stream().mapToInt(RecipeOption.Ingredient::amount).sum();
            features[RECIPE_INGREDIENT_UNITS] = Math.min(16, totalUnits / 9.0);
            features[RECIPE_INGREDIENT_GROUPS] = Math.min(16, recipe.ingredients().size() / 9.0);
        }
    }
}
