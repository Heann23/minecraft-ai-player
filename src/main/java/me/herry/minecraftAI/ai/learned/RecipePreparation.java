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
        PpoBridge.RecipeStock best = null;
        for (var recipe : crafting.recipeOptions(target)) {
            var candidate = measure(recipe, stock);
            if (candidate != null && (best == null || candidate.fraction() > best.fraction()))
                best = candidate;
        }
        return best;
    }

    static PpoBridge.RecipeStock measure(RecipeOption recipe, Map<String, Integer> stock) {
        if (recipe.smelting() || !recipe.countPlannable()) return null;
        Map<List<String>, Integer> groups = new LinkedHashMap<>();
        for (var ingredient : recipe.ingredients())
            groups.merge(ingredient.alternatives(), ingredient.amount(), Integer::sum);
        var seen = new HashSet<String>();
        int required = 0;
        int filled = 0;
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
        return required <= 9 ? new PpoBridge.RecipeStock(recipe.id(), filled, required) : null;
    }
}
