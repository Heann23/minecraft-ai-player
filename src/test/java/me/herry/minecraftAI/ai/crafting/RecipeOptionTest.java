package me.herry.minecraftAI.ai.crafting;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecipeOptionTest {
    @Test void AlternativeMaterialsDoNotBecomeSeveralMandatoryIngredients() {
        var option = new RecipeOption("test:tool", "TOOL", 1, true, false,
                List.of(new RecipeOption.Ingredient(3, List.of("OAK_PLANKS", "SPRUCE_PLANKS"), false),
                        new RecipeOption.Ingredient(2, List.of("STICK"), false)));
        assertEquals(2, option.ingredients().size());
        assertEquals(3, option.ingredients().getFirst().amount());
        assertEquals(2, option.ingredients().getFirst().alternatives().size());
    }

    @Test void CustomItemComponentsCannotBeSatisfiedByMaterialCountsAlone() {
        var choice = new RecipeOption.Ingredient(1, List.of("POTION"), true);
        var option = new RecipeOption("test:exact", "RESULT", 1, false, false, List.of(choice));
        assertFalse(option.countPlannable());
    }

    @Test void RecipeKnowledgeIsImmutableAndPreservesOutputQuantity() {
        var materials = new ArrayList<>(List.of("LOG"));
        var ingredients = new ArrayList<>(List.of(new RecipeOption.Ingredient(1, materials, false)));
        var option = new RecipeOption("test:planks", "PLANKS", 4, false, false, ingredients);
        materials.clear(); ingredients.clear();
        assertEquals(4, option.resultAmount());
        assertEquals(List.of("LOG"), option.ingredients().getFirst().alternatives());
        assertThrows(UnsupportedOperationException.class, () -> option.ingredients().clear());
    }

    @Test void FeasibleFilterExcludesUncraftableTableAndMissingIngredientRecipesWithoutFalseNegatives() {
        var table = new RecipeOption("minecraft:crafting_table", "CRAFTING_TABLE", 1, false, false,
                List.of(new RecipeOption.Ingredient(4, List.of("OAK_PLANKS", "BIRCH_PLANKS"), false)));
        var pickaxe = new RecipeOption("minecraft:wooden_pickaxe", "WOODEN_PICKAXE", 1, true, false,
                List.of(new RecipeOption.Ingredient(3, List.of("OAK_PLANKS", "BIRCH_PLANKS"), false),
                        new RecipeOption.Ingredient(2, List.of("STICK"), false)));
        var ironPickaxe = new RecipeOption("minecraft:iron_pickaxe", "IRON_PICKAXE", 1, true, false,
                List.of(new RecipeOption.Ingredient(3, List.of("IRON_INGOT"), false),
                        new RecipeOption.Ingredient(2, List.of("STICK"), false)));
        var overlapping = new RecipeOption("test:overlap", "OVERLAP", 1, false, false,
                List.of(new RecipeOption.Ingredient(1, List.of("OAK_PLANKS", "BIRCH_PLANKS"), false),
                        new RecipeOption.Ingredient(1, List.of("OAK_PLANKS"), false)));
        var all = List.of(table, pickaxe, ironPickaxe, overlapping);

        var stock = java.util.Map.of("BIRCH_PLANKS", 4, "STICK", 2);
        var withoutTable = CraftingSystem.filterFeasible(all, stock, () -> false);
        assertEquals(List.of(table), withoutTable);

        var withTable = CraftingSystem.filterFeasible(all, stock, () -> true);
        assertEquals(List.of(table, pickaxe), withTable);

        // Overlapping alternatives: OAK_PLANKS=1, BIRCH_PLANKS=1 satisfies both groups without false negative
        var overlapStock = java.util.Map.of("OAK_PLANKS", 1, "BIRCH_PLANKS", 1);
        assertTrue(CraftingSystem.hasRequiredIngredients(overlapping, overlapStock));
        // Only OAK_PLANKS=1 fails union count (1 < 2)
        assertFalse(CraftingSystem.hasRequiredIngredients(overlapping, java.util.Map.of("OAK_PLANKS", 1)));
    }
}
