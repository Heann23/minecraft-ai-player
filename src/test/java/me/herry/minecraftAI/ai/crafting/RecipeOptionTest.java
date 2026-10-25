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
}
