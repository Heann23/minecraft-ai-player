package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.crafting.RecipeOption;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual ingredient availability across all owned inventory slots at a PPO endpoint. */
final class RecipePreparation {
    private RecipePreparation() {}

    static Map<String, Integer> preparationStock(PolicyCandidates.Observed observed) {
        return observed.stock();
    }

    static PpoBridge.RecipeStock observe(CraftingSystem crafting, PolicyCandidates.Observed observed,
                                         PolicyTaskMemory.View task) {
        if (task == null || task.objective() == null || !task.worldMatches()) return null;
        Material target = Material.getMaterial(task.objective().goal().item());
        if (target == null) return null;
        // Include worn/off-hand slots from observed.stock() so slot-40 ping-pong swaps never manufacture reward.
        Map<String, Integer> stock = preparationStock(observed);
        int goalCount = task.objective().goal().count();
        int ownedGoal = Math.max(0, stock.getOrDefault(target.name(), 0));
        boolean tableReachable = observed.features()[WorldPolicyContext.TABLE_REACHABLE] == 1;
        boolean ownsTable = stock.getOrDefault(Material.CRAFTING_TABLE.name(), 0) > 0;
        boolean furnaceReachable = observed.features()[WorldPolicyContext.FURNACE_REACHABLE] == 1;
        boolean ownsFurnace = stock.getOrDefault(Material.FURNACE.name(), 0) > 0;
        var tableOptions = crafting.recipeOptions(Material.CRAFTING_TABLE);
        var furnaceOptions = crafting.recipeOptions(Material.FURNACE);
        PpoBridge.RecipeStock best = null;
        for (var smelt : crafting.smeltingOptions(target)) {
            var candidate = withFurnace(smelt, furnaceOptions, stock, furnaceReachable, ownsFurnace, goalCount, ownedGoal);
            if (candidate != null && (best == null || candidate.fraction() > best.fraction()))
                best = candidate;
        }
        for (var recipe : crafting.recipeOptions(target)) {
            if (isStorageUnpack(recipe)) continue;
            var candidate = recipe.needsTable()
                    ? withTable(recipe, tableOptions, stock, tableReachable, ownsTable)
                    : measure(recipe, stock);
            if (candidate != null && (best == null || candidate.fraction() > best.fraction()))
                best = candidate;
        }
        return best != null ? best : directCollection(target, goalCount, ownedGoal);
    }

    static boolean isStorageUnpack(RecipeOption recipe) {
        return !recipe.smelting() && recipe.ingredients().size() == 1
                && recipe.ingredients().getFirst().amount() == 1 && recipe.resultAmount() == 9;
    }

    static PpoBridge.RecipeStock directCollection(Material target, int goalCount, int ownedGoal) {
        if (target == null || goalCount < 1) return null;
        int capped = Math.min(18, goalCount);
        return new PpoBridge.RecipeStock(target.getKey().toString(), Math.min(capped, Math.max(0, ownedGoal)), capped);
    }

    static PpoBridge.RecipeStock withFurnace(RecipeOption smelt, List<RecipeOption> furnaceOptions,
                                             Map<String, Integer> stock, boolean furnaceReachable,
                                             boolean ownsFurnace, int goalCount, int ownedGoal) {
        if (!smelt.smelting() || !smelt.countPlannable() || smelt.ingredients().size() != 1 || goalCount < 1)
            return null;
        int units = Math.min(goalCount, 9);
        int securedOutput = Math.min(units, Math.max(0, ownedGoal));
        int neededInputs = units - securedOutput;
        boolean hasFurnace = furnaceReachable || ownsFurnace;
        int extraRequired = furnaceReachable ? 0 : 1;
        List<RecipeOption.Ingredient> smeltIngredients = neededInputs > 0
                ? List.of(new RecipeOption.Ingredient(neededInputs, smelt.ingredients().getFirst().alternatives(), false))
                : List.of();
        PpoBridge.RecipeStock best = null;
        for (var furnace : furnaceOptions) {
            if (furnace.smelting() || !furnace.countPlannable()) continue;
            int furnaceUnits = furnace.ingredients().stream().mapToInt(RecipeOption.Ingredient::amount).sum();
            int securedFurnace = furnaceReachable ? furnaceUnits + 1 : ownsFurnace ? furnaceUnits : 0;
            var candidate = measure(smelt.id(), smeltIngredients, hasFurnace ? List.of() : furnace.ingredients(),
                    stock, securedFurnace + securedOutput, extraRequired);
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

    private static boolean sharesIngredients(RecipeOption recipe, RecipeOption workstation) {
        var materials = new HashSet<String>();
        for (var ingredient : recipe.ingredients()) materials.addAll(ingredient.alternatives());
        for (var ingredient : workstation.ingredients()) {
            for (String alt : ingredient.alternatives()) {
                if (materials.contains(alt)) return true;
            }
        }
        return false;
    }

    static PpoBridge.RecipeStock withTable(RecipeOption recipe, List<RecipeOption> tableOptions,
                                          Map<String, Integer> stock, boolean tableReachable, boolean ownsTable) {
        if (recipe.smelting() || !recipe.countPlannable()) return null;
        boolean hasTable = tableReachable || ownsTable;
        int extraRequired = tableReachable ? 0 : 1;
        PpoBridge.RecipeStock best = null;
        for (var table : tableOptions) {
            if (table.smelting() || table.needsTable() || !table.countPlannable()) continue;
            PpoBridge.RecipeStock candidate;
            if (sharesIngredients(recipe, table)) {
                int tableUnits = table.ingredients().stream().mapToInt(RecipeOption.Ingredient::amount).sum();
                int securedUnits = tableReachable ? tableUnits + 1 : ownsTable ? tableUnits : 0;
                candidate = measure(recipe.id(), recipe.ingredients(), hasTable ? List.of() : table.ingredients(),
                        stock, securedUnits, extraRequired);
            } else {
                var base = measure(recipe, stock);
                if (base == null || base.required() + 2 > 18) continue;
                int tableCredit = base.filled() == base.required() ? (tableReachable ? 2 : ownsTable ? 1 : 0) : 0;
                candidate = new PpoBridge.RecipeStock(recipe.id(), base.filled() + tableCredit, base.required() + 2);
            }
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
