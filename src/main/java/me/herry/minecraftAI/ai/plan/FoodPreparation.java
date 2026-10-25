package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.crafting.FuelMath;
import me.herry.minecraftAI.ai.crafting.RecipeOption;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Main-thread food preparation from currently owned ingredients; no acquisition or predicted food is credited. */
public final class FoodPreparation {
    private static final int MATERIALS_PER_TICK = 24;
    private static final int RECIPES_PER_DECISION = 16;
    private static final int MAX_RECIPES = 256;
    private static final int MAX_CACHES = 16;
    private static final Material[] MATERIALS = Material.values();
    // Reading common food outputs first makes the first grain harvest useful before the whole catalogue is warm.
    // Actual ingredients and recipe availability always come from the server registry.
    private static final List<Material> FIRST_OUTPUTS = List.of(Material.BREAD, Material.BEETROOT_SOUP,
            Material.MUSHROOM_STEW, Material.PUMPKIN_PIE, Material.COOKIE, Material.RABBIT_STEW);
    private static final Map<CraftingSystem, Catalogue> CATALOGUES = new WeakHashMap<>();

    private FoodPreparation() {}

    /** May be called from the normal think loop, even while another action is running. */
    public static void advance(AIPlayer ai) {
        if (ai.getWorkBudget().isExhausted()) return;
        Catalogue catalogue = catalogue(ai.getCrafting());
        if (catalogue.warmedAt == ai.getTicks()) return;
        catalogue.warmedAt = ai.getTicks();
        int remaining = MATERIALS_PER_TICK;
        while (catalogue.recipes.size() < MAX_RECIPES && !ai.getWorkBudget().isExhausted()) {
            if (catalogue.optionCursor < catalogue.pendingOptions.size()) {
                RecipeOption option = catalogue.pendingOptions.get(catalogue.optionCursor++);
                if (option.countPlannable()) catalogue.recipes.add(option);
                continue;
            }
            if (remaining-- <= 0 || catalogue.materialCursor >= MATERIALS.length) break;
            Material target = catalogue.priorityCursor < FIRST_OUTPUTS.size()
                    ? FIRST_OUTPUTS.get(catalogue.priorityCursor++) : MATERIALS[catalogue.materialCursor++];
            if (!catalogue.seen.add(target) || !InventorySystem.isSafeFood(target)) continue;
            catalogue.pendingOptions = ai.getCrafting().recipeOptions(target);
            catalogue.optionCursor = 0;
        }
    }

    /** Selects one registered food process, or the collection of this AI's food furnace job. */
    public static List<Action> prepare(AIPlayer ai) {
        advance(ai);
        if (ai.getWorkBudget().isExhausted()) return List.of();
        Catalogue catalogue = catalogue(ai.getCrafting());
        Map<Material, Integer> stock = ai.getInventory().snapshot();
        if (!stock.equals(catalogue.checkedStock)) {
            catalogue.checkedStock = Map.copyOf(stock);
            catalogue.recipeCursor = 0;
        }
        int remaining = Math.min(RECIPES_PER_DECISION, catalogue.recipes.size());
        while (remaining-- > 0 && !ai.getWorkBudget().isExhausted()) {
            RecipeOption option = catalogue.recipes.get(catalogue.recipeCursor++ % catalogue.recipes.size());
            Material target = Material.matchMaterial(option.result());
            if (target == null || !InventorySystem.isSafeFood(target)
                    || ai.getCrafting().singleStep(target, option.id(), stock) == null) continue;
            List<Action> actions = CraftPlans.craftRecipe(ai, target, option.id());
            if (!actions.isEmpty()) return actions;
        }

        var activeJob = FurnacePlans.activeJob(ai);
        if (activeJob != null) {
            Material result = Material.matchMaterial(activeJob.result());
            // Legacy food jobs may not store the output name. Collection still observes the real furnace contents.
            return activeJob.food() || result != null && InventorySystem.isSafeFood(result)
                    ? FurnacePlans.tendFurnace(ai) : List.of();
        }

        List<Material> inputs = stock.keySet().stream().sorted(Comparator.comparing(Material::name)).toList();
        for (Material input : inputs) {
            if (ai.getWorkBudget().isExhausted()) return List.of();
            if (stock.getOrDefault(input, 0) <= 0) continue;
            Material result = ai.getCrafting().smeltingResult(input);
            if (result == null || !InventorySystem.isSafeFood(result)) continue;
            Material fuel = selectFuel(stock, input);
            if (fuel == null) continue;
            // One input gives the earliest real cooked item; insertion is not food acquisition.
            List<Action> actions = CraftPlans.smeltItem(ai, input, 1, fuel);
            if (!actions.isEmpty()) return actions;
        }
        return List.of();
    }

    private static Material selectFuel(Map<Material, Integer> stock, Material input) {
        return stock.keySet().stream().filter(InventorySystem::isFuel)
                .filter(fuel -> fuelAvailable(stock.getOrDefault(fuel, 0), fuel == input,
                        fuel == Material.COAL || fuel == Material.CHARCOAL ? FuelMath.ITEMS_PER_COAL : FuelMath.ITEMS_PER_WOOD))
                .min(Comparator.comparingDouble(FoodPreparation::fuelCost).thenComparing(Material::name)).orElse(null);
    }

    /** Input and fuel must not both reserve the same last item. */
    static boolean fuelAvailable(int count, boolean sharedWithInput, double itemsPerFuel) {
        return count >= FuelMath.fuelFor(1, itemsPerFuel) + (sharedWithInput ? 1 : 0);
    }

    private static double fuelCost(Material fuel) {
        if (Tag.PLANKS.isTagged(fuel)) return 1;
        if (fuel == Material.COAL || fuel == Material.CHARCOAL) return 2;
        return 4;
    }

    private static Catalogue catalogue(CraftingSystem crafting) {
        Catalogue catalogue = CATALOGUES.get(crafting);
        if (catalogue != null && catalogue.revision == crafting.revision()) return catalogue;
        if (CATALOGUES.size() >= MAX_CACHES) CATALOGUES.remove(CATALOGUES.keySet().iterator().next());
        catalogue = new Catalogue(crafting.revision());
        CATALOGUES.put(crafting, catalogue);
        return catalogue;
    }

    /** Values deliberately do not retain the weak CraftingSystem key. All callers run on the server thread. */
    private static final class Catalogue {
        final long revision;
        final Set<Material> seen = EnumSet.noneOf(Material.class);
        final List<RecipeOption> recipes = new ArrayList<>();
        List<RecipeOption> pendingOptions = List.of();
        Map<Material, Integer> checkedStock = Map.of();
        long warmedAt = Long.MIN_VALUE;
        int materialCursor;
        int priorityCursor;
        int recipeCursor;
        int optionCursor;

        Catalogue(long revision) { this.revision = revision; }
    }
}
