package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.crafting.RecipeOption;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CandidateActionContextTest {
    @Test void craftingCandidatesSeparateTableFromCollidingButtonAndExposeObjectiveMatch() {
        UUID world = UUID.randomUUID();
        var memory = new PolicyTaskMemory();
        memory.acquire(world, "CRAFTING_TABLE", 1, 10);
        var stock = Map.of("BIRCH_PLANKS", 4);
        var task = memory.observe(world, stock);
        var context = new CandidateActionContext.Context(stock, task, true, Set.of("BIRCH_PLANKS", "OAK_PLANKS"));

        var table = new RecipeOption("minecraft:crafting_table", "CRAFTING_TABLE", 1, false, false,
                List.of(new RecipeOption.Ingredient(4, List.of("BIRCH_PLANKS", "OAK_PLANKS"), false)));
        var button = new RecipeOption("minecraft:birch_button", "BIRCH_BUTTON", 1, false, false,
                List.of(new RecipeOption.Ingredient(1, List.of("BIRCH_PLANKS"), false)));
        var planks = new RecipeOption("minecraft:birch_planks", "BIRCH_PLANKS", 4, false, false,
                List.of(new RecipeOption.Ingredient(1, List.of("BIRCH_LOG"), false)));

        double[] tableFeatures = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(tableFeatures, PrimitiveType.CRAFT_ITEM, table.result(), table, context);
        assertEquals(1, tableFeatures[CandidateActionContext.HAS_MATERIAL]);
        assertEquals(0, tableFeatures[CandidateActionContext.OWNED_MATERIAL]);
        assertEquals(1, tableFeatures[CandidateActionContext.UNOWNED_MATERIAL]);
        assertEquals(1, tableFeatures[CandidateActionContext.OBJECTIVE_DIRECT_MATCH]);
        assertEquals(0, tableFeatures[CandidateActionContext.OBJECTIVE_INGREDIENT_MATCH]);
        assertEquals(0, tableFeatures[CandidateActionContext.CONSUMES_OBJECTIVE_ITEM]);
        assertEquals(0, tableFeatures[CandidateActionContext.RECIPE_NEEDS_TABLE]);
        assertEquals(4 / 9.0, tableFeatures[CandidateActionContext.RECIPE_INGREDIENT_UNITS], 1e-12);
        assertEquals(1 / 9.0, tableFeatures[CandidateActionContext.RECIPE_INGREDIENT_GROUPS], 1e-12);

        double[] buttonFeatures = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(buttonFeatures, PrimitiveType.CRAFT_ITEM, button.result(), button, context);
        assertEquals(0, buttonFeatures[CandidateActionContext.OBJECTIVE_DIRECT_MATCH]);
        assertEquals(0, buttonFeatures[CandidateActionContext.OBJECTIVE_INGREDIENT_MATCH]);
        assertEquals(1 / 9.0, buttonFeatures[CandidateActionContext.RECIPE_INGREDIENT_UNITS], 1e-12);

        double[] planksFeatures = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(planksFeatures, PrimitiveType.CRAFT_ITEM, planks.result(), planks, context);
        assertEquals(4 / 64.0, planksFeatures[CandidateActionContext.OWNED_MATERIAL], 1e-12);
        assertEquals(0, planksFeatures[CandidateActionContext.UNOWNED_MATERIAL]);
        assertEquals(0, planksFeatures[CandidateActionContext.OBJECTIVE_DIRECT_MATCH]);
        assertEquals(1, planksFeatures[CandidateActionContext.OBJECTIVE_INGREDIENT_MATCH]);
    }

    @Test void objectiveMaskingZeroesOnlyObjectiveRelationsAndPreservesMaterialAndRecipeFacts() {
        UUID world = UUID.randomUUID();
        var memory = new PolicyTaskMemory();
        memory.acquire(world, "CRAFTING_TABLE", 1, 10);
        var stock = Map.of("BIRCH_PLANKS", 4);
        var task = memory.observe(world, stock);
        var visible = new CandidateActionContext.Context(stock, task, true, Set.of("BIRCH_PLANKS"));
        var masked = new CandidateActionContext.Context(stock, task, false, Set.of("BIRCH_PLANKS"));
        var table = new RecipeOption("minecraft:crafting_table", "CRAFTING_TABLE", 1, false, false,
                List.of(new RecipeOption.Ingredient(4, List.of("BIRCH_PLANKS"), false)));

        double[] visibleFeatures = new double[PolicyFrame.ACTION_SIZE];
        double[] maskedFeatures = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(visibleFeatures, PrimitiveType.CRAFT_ITEM, table.result(), table, visible);
        CandidateActionContext.encode(maskedFeatures, PrimitiveType.CRAFT_ITEM, table.result(), table, masked);

        assertEquals(1, visibleFeatures[CandidateActionContext.OBJECTIVE_DIRECT_MATCH]);
        assertEquals(0, maskedFeatures[CandidateActionContext.OBJECTIVE_DIRECT_MATCH]);
        assertEquals(0, maskedFeatures[CandidateActionContext.OBJECTIVE_INGREDIENT_MATCH]);
        assertEquals(0, maskedFeatures[CandidateActionContext.CONSUMES_OBJECTIVE_ITEM]);
        for (int i = 0; i < PolicyFrame.ACTION_SIZE; i++) {
            if (i < CandidateActionContext.OBJECTIVE_DIRECT_MATCH || i > CandidateActionContext.CONSUMES_OBJECTIVE_ITEM) {
                assertEquals(visibleFeatures[i], maskedFeatures[i], "index " + i);
            }
        }
    }

    @Test void achievedObjectiveDisablesAcquisitionMatchWhileFlaggingPlacementConsumptionAndWorldMismatch() {
        UUID world = UUID.randomUUID();
        var memory = new PolicyTaskMemory();
        memory.acquire(world, "CRAFTING_TABLE", 1, 10);
        var ownedStock = Map.of("CRAFTING_TABLE", 1, "OAK_PLANKS", 4);
        var achieved = memory.observe(world, ownedStock);
        var context = new CandidateActionContext.Context(ownedStock, achieved, true, Set.of("OAK_PLANKS"));
        var table = new RecipeOption("minecraft:crafting_table", "CRAFTING_TABLE", 1, false, false,
                List.of(new RecipeOption.Ingredient(4, List.of("OAK_PLANKS"), false)));

        double[] craftFeatures = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(craftFeatures, PrimitiveType.CRAFT_ITEM, "CRAFTING_TABLE", table, context);
        assertEquals(0, craftFeatures[CandidateActionContext.OBJECTIVE_DIRECT_MATCH]);
        assertEquals(0, craftFeatures[CandidateActionContext.OBJECTIVE_INGREDIENT_MATCH]);

        double[] placeFeatures = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(placeFeatures, PrimitiveType.PLACE_BLOCK, "CRAFTING_TABLE", null, context);
        assertEquals(1, placeFeatures[CandidateActionContext.CONSUMES_OBJECTIVE_ITEM]);

        var otherWorld = memory.observe(UUID.randomUUID(), Map.of());
        var mismatchContext = new CandidateActionContext.Context(Map.of(), otherWorld, true, Set.of("OAK_PLANKS"));
        double[] mineFeatures = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(mineFeatures, PrimitiveType.BREAK_BLOCK, "CRAFTING_TABLE", null, mismatchContext);
        assertEquals(0, mineFeatures[CandidateActionContext.OBJECTIVE_DIRECT_MATCH]);
        assertEquals(0, mineFeatures[CandidateActionContext.OBJECTIVE_INGREDIENT_MATCH]);
        assertEquals(0, mineFeatures[CandidateActionContext.CONSUMES_OBJECTIVE_ITEM]);
    }
}
