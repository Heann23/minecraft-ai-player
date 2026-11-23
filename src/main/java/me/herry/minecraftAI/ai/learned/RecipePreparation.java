package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.crafting.RecipeOption;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual ingredient availability in crafting-accessible slots at a PPO endpoint. */
final class RecipePreparation {
    private RecipePreparation() {}

    static PpoBridge.RecipeStock observe(CraftingSystem crafting, PolicyCandidates.Observed observed,
                                         PolicyTaskMemory.View task) {
        if (task == null || task.objective() == null || !task.worldMatches()) return null;
        Material target = Material.getMaterial(task.objective().goal().item());
        if (target == null) return null;
        Map<String, Integer> stock = new HashMap<>();
        for (var slot : observed.snapshot().inventory()) {
            if (slot.slot() < 36 && slot.amount() > 0) stock.merge(slot.material(), slot.amount(), Integer::sum);
        }
        boolean tableReachable = observed.features()[WorldPolicyContext.TABLE_REACHABLE] == 1;
        boolean ownsTable = stock.getOrDefault(Material.CRAFTING_TABLE.name(), 0) > 0;
        var tableOptions = crafting.recipeOptions(Material.CRAFTING_TABLE);
        PpoBridge.RecipeStock best = null;
        for (var recipe : crafting.recipeOptions(target)) {
            var candidate = recipe.needsTable()
                    ? withTable(recipe, tableOptions, stock, tableReachable, ownsTable)
                    : measure(recipe, stock);
            if (candidate != null && (best == null || candidate.fraction() > best.fraction()))
                best = candidate;
        }
        return best;
    }

    static PpoBridge.RecipeStock measure(RecipeOption recipe, Map<String, Integer> stock) {
        if (recipe.smelting() || !recipe.countPlannable()) return null;
        return measure(recipe.id(), recipe.ingredients(), List.of(), stock, 0);
    }

    static PpoBridge.RecipeStock withTable(RecipeOption recipe, List<RecipeOption> tableOptions,
                                          Map<String, Integer> stock, boolean hasTable) {
        if (recipe.smelting() || !recipe.countPlannable()) return null;
        PpoBridge.RecipeStock best = null;
        for (var table : tableOptions) {
            if (table.smelting() || table.needsTable() || !table.countPlannable()) continue;
            int tableUnits = table.ingredients().stream().mapToInt(RecipeOption.Ingredient::amount).sum();
            var candidate = measure(recipe.id(), recipe.ingredients(), hasTable ? List.of() : table.ingredients(),
                    stock, hasTable ? tableUnits : 0);
            if (candidate != null && (best == null || candidate.fraction() > best.fraction()))
                best = candidate;
        }
        return best;
    }

    static PpoBridge.RecipeStock withTable(RecipeOption recipe, List<RecipeOption> tableOptions,
                                          Map<String, Integer> stock, boolean tableReachable, boolean ownsTable) {
        if (recipe.smelting() || !recipe.countPlannable()) return null;
        boolean hasTable = tableReachable || ownsTable;
        int extraRequired = tableReachable ? 0 : 1;
        PpoBridge.RecipeStock best = null;
        for (var table : tableOptions) {
            if (table.smelting() || table.needsTable() || !table.countPlannable()) continue;
            int tableUnits = table.ingredients().stream().mapToInt(RecipeOption.Ingredient::amount).sum();
            int securedUnits = tableReachable ? tableUnits + 1 : ownsTable ? tableUnits : 0;
            var candidate = measure(recipe.id(), recipe.ingredients(), hasTable ? List.of() : table.ingredients(),
                    stock, securedUnits, extraRequired);
            if (candidate != null && (best == null || candidate.fraction() > best.fraction()))
                best = candidate;
        }
        return best;
    }

    private static PpoBridge.RecipeStock measure(String recipe, List<RecipeOption.Ingredient> ingredients,
                                                List<RecipeOption.Ingredient> extra, Map<String, Integer> stock,
                                                int securedUnits) {
        return measure(recipe, ingredients, extra, stock, securedUnits, 0);
    }

    private static PpoBridge.RecipeStock measure(String recipe, List<RecipeOption.Ingredient> ingredients,
                                                List<RecipeOption.Ingredient> extra, Map<String, Integer> stock,
                                                int securedUnits, int extraRequired) {
        Map<List<String>, Integer> groups = new LinkedHashMap<>();
        for (var ingredient : ingredients)
            groups.merge(ingredient.alternatives(), ingredient.amount(), Integer::sum);
        for (var ingredient : extra)
            groups.merge(ingredient.alternatives(), ingredient.amount(), Integer::sum);
        var seen = new HashSet<String>();
        int required = securedUnits + extraRequired;
        int filled = securedUnits;
        for (var group : groups.entrySet()) {
            int available = 0;
            for (String material : group.getKey()) {
                // Shared alternatives between different groups need joint allocation.
                if (!seen.add(material)) return null;
                available += Math.max(0, stock.getOrDefault(material, 0));
            }
            required += group.getValue();
            filled += Math.min(group.getValue(), available);
        }
        return required <= 18 ? new PpoBridge.RecipeStock(recipe, filled, required) : null;
    }
}
