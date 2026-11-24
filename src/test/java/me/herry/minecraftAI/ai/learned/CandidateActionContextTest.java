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
    @Test void actualRecipeStockCapsSurplusAndCountsMixedPlanksOnlyOnce() {
        var planks = new RecipeOption.Ingredient(1, List.of("BIRCH_PLANKS", "OAK_PLANKS"), false);
        var sticks = new RecipeOption.Ingredient(2, List.of("STICK"), false);
        var pickaxe = new RecipeOption("minecraft:wooden_pickaxe", "WOODEN_PICKAXE", 1, true, false,
                List.of(planks, planks, planks, sticks));
        var before = RecipePreparation.measure(pickaxe, Map.of("BIRCH_PLANKS", 1, "OAK_PLANKS", 2, "STICK", 4));
        var after = RecipePreparation.measure(pickaxe, Map.of("OAK_PLANKS", 1, "STICK", 8));
        assertEquals(5, before.filled());
        assertEquals(3, after.filled());
        assertEquals(-0.2, new PpoBridge.RecipeProgress(before, after, false).reward(0, false), 1e-9);
        assertEquals(0, new PpoBridge.RecipeProgress(before, after, true).reward(0, false), 1e-9);
        var overlapping = new RecipeOption("test:overlap", "WOODEN_PICKAXE", 1, true, false,
                List.of(planks, new RecipeOption.Ingredient(1, List.of("BIRCH_PLANKS"), false)));
        assertNull(RecipePreparation.measure(overlapping, Map.of("BIRCH_PLANKS", 1)));
    }

    @Test void securingATablePreservesProgressAndSharedPlanksAreNotCountedTwice() {
        var woods = List.of("BIRCH_PLANKS", "OAK_PLANKS");
        var pickaxe = new RecipeOption("minecraft:wooden_pickaxe", "WOODEN_PICKAXE", 1, true, false, List.of(
                new RecipeOption.Ingredient(3, woods, false),
                new RecipeOption.Ingredient(2, List.of("STICK"), false)));
        var table = new RecipeOption("minecraft:crafting_table", "CRAFTING_TABLE", 1, false, false,
                List.of(new RecipeOption.Ingredient(4, woods, false)));
        var materialBefore = RecipePreparation.withTable(pickaxe, List.of(table), Map.of("OAK_PLANKS", 4, "STICK", 2), false);
        var materialAfter = RecipePreparation.withTable(pickaxe, List.of(table), Map.of("STICK", 2), true);
        assertEquals(9, materialBefore.required());
        assertEquals(6, materialBefore.filled());
        assertEquals(materialBefore, materialAfter);
        assertEquals(9, RecipePreparation.withTable(pickaxe, List.of(table),
                Map.of("BIRCH_PLANKS", 3, "STICK", 2), true).filled());
    }

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

    @Test void materialImpactDetectsIngredientDepletionAndFeasibilityLossAcrossRecipesAndPlacements() {
        UUID world = UUID.randomUUID();
        var memory = new PolicyTaskMemory();
        memory.acquire(world, "WOODEN_PICKAXE", 1, 10);

        var planks = List.of("BIRCH_PLANKS", "OAK_PLANKS", "SPRUCE_PLANKS");
        var pickaxeRecipe = new RecipeOption("minecraft:wooden_pickaxe", "WOODEN_PICKAXE", 1, true, false,
                List.of(new RecipeOption.Ingredient(3, planks, false),
                        new RecipeOption.Ingredient(2, List.of("STICK"), false)));
        var tableRecipe = new RecipeOption("minecraft:crafting_table", "CRAFTING_TABLE", 1, false, false,
                List.of(new RecipeOption.Ingredient(4, planks, false)));
        var buttonRecipe = new RecipeOption("minecraft:birch_button", "BIRCH_BUTTON", 1, false, false,
                List.of(new RecipeOption.Ingredient(1, List.of("BIRCH_PLANKS"), false)));
        var stickRecipe = new RecipeOption("minecraft:stick", "STICK", 4, false, false,
                List.of(new RecipeOption.Ingredient(2, planks, false)));
        var planksRecipe = new RecipeOption("minecraft:birch_planks", "BIRCH_PLANKS", 4, false, false,
                List.of(new RecipeOption.Ingredient(1, List.of("BIRCH_LOG"), false)));

        // Case 1: 4 birch planks, 0 sticks, missing crafting table (exact v493 held-out bottleneck).
        var fourPlanks = Map.of("BIRCH_PLANKS", 4);
        var task4 = memory.observe(world, fourPlanks);
        var ctx4 = new CandidateActionContext.Context(fourPlanks, task4, true,
                Set.of("BIRCH_PLANKS", "OAK_PLANKS", "SPRUCE_PLANKS", "STICK"), Set.of("BIRCH_LOG"), true,
                fourPlanks, List.of(new CandidateActionContext.SlotAmount(0, "BIRCH_PLANKS", 4)),
                List.of(pickaxeRecipe), List.of(tableRecipe));

        var buttonDelta = CandidateActionContext.materialDelta(PrimitiveType.CRAFT_ITEM, buttonRecipe, null, null, 0,
                ctx4.storageStock(), ctx4.storageSlots());
        assertEquals(Map.of("BIRCH_PLANKS", 1), buttonDelta.consumed());
        assertEquals(Map.of("BIRCH_BUTTON", 1), buttonDelta.produced());
        double[] buttonF = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(buttonF, buttonDelta, ctx4, true);
        assertEquals(1, buttonF[CandidateActionContext.CONSUMES_OBJECTIVE_INGREDIENT]);
        assertEquals(1 / 9.0, buttonF[CandidateActionContext.OBJECTIVE_INGREDIENT_CONSUMED_UNITS], 1e-12);
        assertEquals(0.0, buttonF[CandidateActionContext.OBJECTIVE_RECIPE_COMPLETION_DELTA], 1e-12);
        assertEquals(0, buttonF[CandidateActionContext.DEPLETES_OBJECTIVE_INGREDIENT_GROUP]);
        assertEquals(1, buttonF[CandidateActionContext.CONSUMES_REQUIRED_TABLE_INGREDIENT]);
        assertEquals(-0.25, buttonF[CandidateActionContext.REQUIRED_TABLE_COMPLETION_DELTA], 1e-12);
        assertEquals(1, buttonF[CandidateActionContext.BREAKS_REQUIRED_TABLE_FEASIBILITY]);

        var stickDelta = CandidateActionContext.materialDelta(PrimitiveType.CRAFT_ITEM, stickRecipe, null, null, 0,
                ctx4.storageStock(), ctx4.storageSlots());
        double[] stickF = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(stickF, stickDelta, ctx4, true);
        assertEquals(1, stickF[CandidateActionContext.CONSUMES_OBJECTIVE_INGREDIENT]);
        assertEquals(2 / 9.0, stickF[CandidateActionContext.OBJECTIVE_INGREDIENT_CONSUMED_UNITS], 1e-12);
        assertEquals(0.20, stickF[CandidateActionContext.OBJECTIVE_RECIPE_COMPLETION_DELTA], 1e-12);
        assertEquals(1, stickF[CandidateActionContext.DEPLETES_OBJECTIVE_INGREDIENT_GROUP]);
        assertEquals(1, stickF[CandidateActionContext.CONSUMES_REQUIRED_TABLE_INGREDIENT]);
        assertEquals(-0.50, stickF[CandidateActionContext.REQUIRED_TABLE_COMPLETION_DELTA], 1e-12);
        assertEquals(1, stickF[CandidateActionContext.BREAKS_REQUIRED_TABLE_FEASIBILITY]);

        // Case 2: 7 birch planks, 2 sticks, missing table — crafting button breaks combined table+pickaxe feasibility.
        var sevenPlanks = Map.of("BIRCH_PLANKS", 7, "STICK", 2);
        var ctx7 = new CandidateActionContext.Context(sevenPlanks, memory.observe(world, sevenPlanks), true,
                Set.of("BIRCH_PLANKS", "OAK_PLANKS", "SPRUCE_PLANKS", "STICK"), Set.of("BIRCH_LOG"), true,
                sevenPlanks, List.of(), List.of(pickaxeRecipe), List.of(tableRecipe));
        double[] button7 = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(button7, buttonDelta, ctx7, true);
        assertEquals(0, button7[CandidateActionContext.BREAKS_REQUIRED_TABLE_FEASIBILITY]);
        assertEquals(0, button7[CandidateActionContext.BREAKS_OBJECTIVE_RECIPE_FEASIBILITY]);
        assertEquals(1, button7[CandidateActionContext.BREAKS_TABLE_AND_OBJECTIVE_FEASIBILITY]);

        var tableDelta = CandidateActionContext.materialDelta(PrimitiveType.CRAFT_ITEM, tableRecipe, null, null, 0,
                ctx7.storageStock(), ctx7.storageSlots());
        double[] table7 = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(table7, tableDelta, ctx7, true);
        assertEquals(0, table7[CandidateActionContext.CONSUMES_REQUIRED_TABLE_INGREDIENT]);
        assertEquals(0, table7[CandidateActionContext.BREAKS_REQUIRED_TABLE_FEASIBILITY]);
        assertEquals(0, table7[CandidateActionContext.BREAKS_OBJECTIVE_RECIPE_FEASIBILITY]);
        assertEquals(0, table7[CandidateActionContext.BREAKS_TABLE_AND_OBJECTIVE_FEASIBILITY]);

        // Case 3: Union trap check — 4 planks + 5 sticks has 9 total items, but NOT 7 planks + 2 sticks.
        assertFalse(CandidateActionContext.anyCombinedFeasible(
                List.of(tableRecipe), List.of(pickaxeRecipe), Map.of("BIRCH_PLANKS", 4, "STICK", 5)));
        assertTrue(CandidateActionContext.anyCombinedFeasible(
                List.of(tableRecipe), List.of(pickaxeRecipe), Map.of("OAK_PLANKS", 3, "BIRCH_PLANKS", 4, "STICK", 2)));

        // Case 4: Indirect ingredient crafting (log -> planks) enables table feasibility without indirect loss flag,
        // whereas placing a log sets CONSUMES_INDIRECT_INGREDIENT.
        var oneLog = Map.of("BIRCH_LOG", 1);
        var ctxLog = new CandidateActionContext.Context(oneLog, memory.observe(world, oneLog), true,
                Set.of("BIRCH_PLANKS", "OAK_PLANKS", "SPRUCE_PLANKS", "STICK"), Set.of("BIRCH_LOG"), true,
                oneLog, List.of(), List.of(pickaxeRecipe), List.of(tableRecipe));
        var planksDelta = CandidateActionContext.materialDelta(PrimitiveType.CRAFT_ITEM, planksRecipe, null, null, 0,
                ctxLog.storageStock(), ctxLog.storageSlots());
        double[] planksF = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(planksF, planksDelta, ctxLog, true);
        assertEquals(0, planksF[CandidateActionContext.CONSUMES_INDIRECT_INGREDIENT]);
        assertEquals(0.60, planksF[CandidateActionContext.OBJECTIVE_RECIPE_COMPLETION_DELTA], 1e-12);
        assertEquals(1.0, planksF[CandidateActionContext.REQUIRED_TABLE_COMPLETION_DELTA], 1e-12);
        assertEquals(1, planksF[CandidateActionContext.ENABLES_REQUIRED_TABLE_FEASIBILITY]);

        var placeLogDelta = new CandidateActionContext.MaterialDelta(Map.of("BIRCH_LOG", 1), Map.of());
        double[] placeLogF = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(placeLogF, placeLogDelta, ctxLog, true);
        assertEquals(1, placeLogF[CandidateActionContext.CONSUMES_INDIRECT_INGREDIENT]);

        // Case 5: Objective masking zeroes every material-impact index 288..300.
        var maskedCtx = new CandidateActionContext.Context(fourPlanks, task4, false,
                Set.of("BIRCH_PLANKS", "STICK"), Set.of("BIRCH_LOG"), true,
                fourPlanks, List.of(), List.of(pickaxeRecipe), List.of(tableRecipe));
        double[] maskedF = new double[PolicyFrame.ACTION_SIZE];
        maskedF[CandidateActionContext.BREAKS_REQUIRED_TABLE_FEASIBILITY] = 1;
        CandidateActionContext.encodeMaterialImpact(maskedF, buttonDelta, maskedCtx, true);
        for (int i = CandidateActionContext.CONSUMES_OBJECTIVE_INGREDIENT;
             i <= CandidateActionContext.ENABLES_TABLE_AND_OBJECTIVE_FEASIBILITY; i++) {
            assertEquals(0.0, maskedF[i], "masked index " + i);
        }
    }

    @Test void smeltingPrecursorTransferDoesNotFlagWastefulConsumptionWhileFuelConsumptionDoes() {
        UUID world = UUID.randomUUID();
        var memory = new PolicyTaskMemory();
        memory.acquire(world, "IRON_PICKAXE", 1, 10);
        var stock = Map.of("RAW_IRON", 3, "OAK_PLANKS", 4, "STICK", 2);
        var pickaxe = new RecipeOption("minecraft:iron_pickaxe", "IRON_PICKAXE", 1, true, false,
                List.of(new RecipeOption.Ingredient(3, List.of("IRON_INGOT"), false),
                        new RecipeOption.Ingredient(2, List.of("STICK"), false)));
        var table = new RecipeOption("minecraft:crafting_table", "CRAFTING_TABLE", 1, false, false,
                List.of(new RecipeOption.Ingredient(4, List.of("OAK_PLANKS"), false)));
        var ctx = new CandidateActionContext.Context(stock, memory.observe(world, stock), true,
                Set.of("IRON_INGOT", "STICK"), Set.of("RAW_IRON", "OAK_PLANKS"), true,
                stock, List.of(), List.of(pickaxe), List.of(table));

        // Putting RAW_IRON into furnace ingredient slot produces IRON_INGOT (direct ingredient) -> NOT wasteful indirect loss.
        var smeltRawIron = new CandidateActionContext.MaterialDelta(Map.of("RAW_IRON", 1), Map.of());
        double[] smeltF = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(smeltF, smeltRawIron, ctx, true, "IRON_INGOT");
        assertEquals(0.0, smeltF[CandidateActionContext.CONSUMES_INDIRECT_INGREDIENT]);

        // Burning OAK_PLANKS as furnace fuel (no smelting output) breaks required crafting table feasibility!
        var burnPlanks = new CandidateActionContext.MaterialDelta(Map.of("OAK_PLANKS", 1), Map.of());
        double[] fuelF = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(fuelF, burnPlanks, ctx, true, null);
        assertEquals(1.0, fuelF[CandidateActionContext.CONSUMES_INDIRECT_INGREDIENT]);
        assertEquals(1.0, fuelF[CandidateActionContext.CONSUMES_REQUIRED_TABLE_INGREDIENT]);
        assertEquals(1.0, fuelF[CandidateActionContext.BREAKS_REQUIRED_TABLE_FEASIBILITY]);
    }

    @Test void bucketFillAndPourProduceExactMaterialDeltasAndObjectiveDirectMatch() {
        UUID world = UUID.randomUUID();
        var memory = new PolicyTaskMemory();
        memory.acquire(world, "WATER_BUCKET", 1, 10);
        var stock = Map.of("BUCKET", 1);
        var ctx = new CandidateActionContext.Context(stock, memory.observe(world, stock), true, Set.of());
        var fill = new me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.FillBucket(
                world, new me.herry.minecraftAI.ai.util.BlockPoint(1, 64, 0), "WATER");
        var delta = CandidateActionContext.materialDelta(PrimitiveType.USE_ITEM, null, fill, null, 0, stock, List.of());
        assertEquals(Map.of("BUCKET", 1), delta.consumed());
        assertEquals(Map.of("WATER_BUCKET", 1), delta.produced());

        double[] fillF = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(fillF, PrimitiveType.USE_ITEM, "WATER_BUCKET", null, ctx, true);
        assertEquals(1.0, fillF[CandidateActionContext.OBJECTIVE_DIRECT_MATCH]);

        var pourStock = Map.of("WATER_BUCKET", 1);
        var pour = new me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.PourBucket(
                world, new me.herry.minecraftAI.ai.util.BlockPoint(1, 63, 0), "WATER_BUCKET");
        var pourDelta = CandidateActionContext.materialDelta(PrimitiveType.USE_ITEM, null, pour, null, 0, pourStock, List.of());
        assertEquals(Map.of("WATER_BUCKET", 1), pourDelta.consumed());
        assertEquals(Map.of("BUCKET", 1), pourDelta.produced());
    }

    @Test void exactDropFlagsObjectiveFeasibilityLossWithoutPenalizingUnrelatedSlots() {
        UUID world = UUID.randomUUID();
        var memory = new PolicyTaskMemory();
        memory.acquire(world, "CRAFTING_TABLE", 1, 10);
        var stock = Map.of("DIRT", 12, "BIRCH_PLANKS", 4);
        var slots = List.of(
                new CandidateActionContext.SlotAmount(0, "DIRT", 12),
                new CandidateActionContext.SlotAmount(1, "BIRCH_PLANKS", 4));
        var tableRecipe = new RecipeOption("minecraft:crafting_table", "CRAFTING_TABLE", 1, false, false,
                List.of(new RecipeOption.Ingredient(4, List.of("BIRCH_PLANKS", "OAK_PLANKS"), false)));
        var ctx = new CandidateActionContext.Context(stock, memory.observe(world, stock), true,
                Set.of("BIRCH_PLANKS", "OAK_PLANKS"), Set.of("BIRCH_LOG"), false,
                stock, slots, List.of(tableRecipe), List.of());

        var dropPlanks = new me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.DropExact(world, 1, "BIRCH_PLANKS");
        var planksDelta = CandidateActionContext.materialDelta(PrimitiveType.DROP_ITEM, null, dropPlanks, null, 0, stock, slots);
        assertEquals(Map.of("BIRCH_PLANKS", 4), planksDelta.consumed());
        double[] pf = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(pf, planksDelta, ctx, false);
        assertEquals(1.0, pf[CandidateActionContext.CONSUMES_OBJECTIVE_INGREDIENT]);
        assertEquals(1.0, pf[CandidateActionContext.BREAKS_OBJECTIVE_RECIPE_FEASIBILITY]);

        var dropDirt = new me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.DropExact(world, 0, "DIRT");
        var dirtDelta = CandidateActionContext.materialDelta(PrimitiveType.DROP_ITEM, null, dropDirt, null, 0, stock, slots);
        assertEquals(Map.of("DIRT", 12), dirtDelta.consumed());
        double[] df = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialImpact(df, dirtDelta, ctx, false);
        for (int i = CandidateActionContext.CONSUMES_OBJECTIVE_INGREDIENT;
             i <= CandidateActionContext.ENABLES_TABLE_AND_OBJECTIVE_FEASIBILITY; i++) {
            assertEquals(0.0, df[i], "unrelated drop idx " + i);
        }
    }

    @Test void placingCraftingTableIncreasesReadinessOverPocketTableAndPenalizesRedundantTable() {
        var woods = List.of("BIRCH_PLANKS", "OAK_PLANKS");
        var pickaxe = new RecipeOption("minecraft:wooden_pickaxe", "WOODEN_PICKAXE", 1, true, false, List.of(
                new RecipeOption.Ingredient(3, woods, false),
                new RecipeOption.Ingredient(2, List.of("STICK"), false)));
        var table = new RecipeOption("minecraft:crafting_table", "CRAFTING_TABLE", 1, false, false,
                List.of(new RecipeOption.Ingredient(4, woods, false)));
        var tables = List.of(table);

        var bareFourPlanks = RecipePreparation.withTable(pickaxe, tables, Map.of("OAK_PLANKS", 4), false, false);
        assertEquals(4, bareFourPlanks.filled());
        assertEquals(10, bareFourPlanks.required());

        var pocketTable = RecipePreparation.withTable(pickaxe, tables, Map.of("CRAFTING_TABLE", 1), false, true);
        assertEquals(4, pocketTable.filled());
        assertEquals(10, pocketTable.required());

        var placedTable = RecipePreparation.withTable(pickaxe, tables, Map.of(), true, false);
        assertEquals(5, placedTable.filled());
        assertEquals(10, placedTable.required());

        var readyPickaxe = RecipePreparation.withTable(pickaxe, tables, Map.of("OAK_PLANKS", 3, "STICK", 2), true, false);
        assertEquals(10, readyPickaxe.filled());
        assertEquals(10, readyPickaxe.required());

        var fourPlanksWithTable = RecipePreparation.withTable(pickaxe, tables, Map.of("OAK_PLANKS", 4), true, false);
        var redundantSecondTable = RecipePreparation.withTable(pickaxe, tables, Map.of("CRAFTING_TABLE", 1), true, true);
        assertEquals(8, fourPlanksWithTable.filled());
        assertEquals(10, fourPlanksWithTable.required());
        assertEquals(5, redundantSecondTable.filled());
        assertEquals(10, redundantSecondTable.required());
        assertTrue(redundantSecondTable.fraction() < fourPlanksWithTable.fraction());
    }

    @Test void smeltingAndDirectCollectionTrackFurnacePlacementRawOreAndMultiUnitTargets() {
        var ironBlock = new RecipeOption("minecraft:raw_iron", "RAW_IRON", 9, false, false,
                List.of(new RecipeOption.Ingredient(1, List.of("RAW_IRON_BLOCK"), false)));
        var oakPlanks = new RecipeOption("minecraft:oak_planks", "OAK_PLANKS", 4, false, false,
                List.of(new RecipeOption.Ingredient(1, List.of("OAK_LOG"), false)));
        var pickaxe = new RecipeOption("minecraft:wooden_pickaxe", "WOODEN_PICKAXE", 1, true, false, List.of(
                new RecipeOption.Ingredient(3, List.of("OAK_PLANKS"), false),
                new RecipeOption.Ingredient(2, List.of("STICK"), false)));
        assertTrue(RecipePreparation.isStorageUnpack(ironBlock));
        assertFalse(RecipePreparation.isStorageUnpack(oakPlanks));
        assertFalse(RecipePreparation.isStorageUnpack(pickaxe));

        var smelt = new RecipeOption("minecraft:iron_ingot_from_smelting_raw_iron", "IRON_INGOT", 1, false, true,
                List.of(new RecipeOption.Ingredient(1, List.of("RAW_IRON"), false)));
        var furnace = new RecipeOption("minecraft:furnace", "FURNACE", 1, true, false,
                List.of(new RecipeOption.Ingredient(8, List.of("COBBLESTONE"), false)));
        var furnaces = List.of(furnace);

        var empty = RecipePreparation.withFurnace(smelt, furnaces, Map.of(), false, false, 3, 0);
        assertEquals(0, empty.filled());
        assertEquals(12, empty.required());

        var cobble = RecipePreparation.withFurnace(smelt, furnaces, Map.of("COBBLESTONE", 8), false, false, 3, 0);
        assertEquals(8, cobble.filled());
        assertEquals(12, cobble.required());

        var ownsFurnace = RecipePreparation.withFurnace(smelt, furnaces, Map.of(), false, true, 3, 0);
        assertEquals(8, ownsFurnace.filled());
        assertEquals(12, ownsFurnace.required());

        var placedFurnace = RecipePreparation.withFurnace(smelt, furnaces, Map.of(), true, false, 3, 0);
        assertEquals(9, placedFurnace.filled());
        assertEquals(12, placedFurnace.required());

        var readySmelt = RecipePreparation.withFurnace(smelt, furnaces, Map.of("RAW_IRON", 3), true, false, 3, 0);
        assertEquals(12, readySmelt.filled());
        assertEquals(12, readySmelt.required());

        var smeltedDone = RecipePreparation.withFurnace(smelt, furnaces, Map.of("IRON_INGOT", 3), true, false, 3, 3);
        assertEquals(12, smeltedDone.filled());
        assertEquals(12, smeltedDone.required());

        var direct = RecipePreparation.directCollection(org.bukkit.Material.RAW_IRON, 3, 2);
        assertEquals("minecraft:raw_iron", direct.recipe());
        assertEquals(2, direct.filled());
        assertEquals(3, direct.required());
    }

    @Test void materialPropertiesEncodeFactualToolHarvestAndRecipeGraphBits() {
        double[] pickaxe = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialProperties(pickaxe,
                new CandidateActionContext.MaterialProperties(0, true, true, true, 4.0, false, false, false, false));
        assertEquals(0.0, pickaxe[CandidateActionContext.MATERIAL_DEPENDENT_RECIPES]);
        assertEquals(1.0, pickaxe[CandidateActionContext.MATERIAL_HAS_DURABILITY]);
        assertEquals(1.0, pickaxe[CandidateActionContext.MATERIAL_HARVESTS_STONE]);
        assertEquals(1.0, pickaxe[CandidateActionContext.MATERIAL_HARVESTS_IRON_ORE]);
        assertEquals(0.25, pickaxe[CandidateActionContext.MATERIAL_STONE_DESTROY_SPEED], 1e-12);
        assertEquals(0.0, pickaxe[CandidateActionContext.MATERIAL_HARVESTS_OBSIDIAN]);
        assertEquals(0.0, pickaxe[CandidateActionContext.MATERIAL_IS_BLOCK]);
        assertEquals(0.0, pickaxe[CandidateActionContext.MATERIAL_IS_FOOD]);
        assertEquals(0.0, pickaxe[CandidateActionContext.MATERIAL_IS_FUEL]);

        double[] intermediate = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialProperties(intermediate,
                new CandidateActionContext.MaterialProperties(20, false, false, false, 1.0, false, true, false, true));
        assertEquals(1.0, intermediate[CandidateActionContext.MATERIAL_DEPENDENT_RECIPES]);
        assertEquals(0.0, intermediate[CandidateActionContext.MATERIAL_HAS_DURABILITY]);
        assertEquals(1.0 / 16.0, intermediate[CandidateActionContext.MATERIAL_STONE_DESTROY_SPEED], 1e-12);
        assertEquals(1.0, intermediate[CandidateActionContext.MATERIAL_IS_BLOCK]);
        assertEquals(0.0, intermediate[CandidateActionContext.MATERIAL_IS_FOOD]);
        assertEquals(1.0, intermediate[CandidateActionContext.MATERIAL_IS_FUEL]);

        double[] blank = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialProperties(blank, null);
        CandidateActionContext.encodeMaterialProperties(blank, CandidateActionContext.MaterialProperties.EMPTY);
        for (int idx : new int[] {
                CandidateActionContext.MATERIAL_DEPENDENT_RECIPES,
                CandidateActionContext.MATERIAL_HAS_DURABILITY,
                CandidateActionContext.MATERIAL_HARVESTS_STONE,
                CandidateActionContext.MATERIAL_HARVESTS_IRON_ORE,
                CandidateActionContext.MATERIAL_STONE_DESTROY_SPEED,
                CandidateActionContext.MATERIAL_HARVESTS_OBSIDIAN,
                CandidateActionContext.MATERIAL_IS_BLOCK,
                CandidateActionContext.MATERIAL_IS_FOOD,
                CandidateActionContext.MATERIAL_IS_FUEL
        }) {
            assertEquals(0.0, blank[idx]);
        }
    }

    @Test void workstationContextAndObservedDropMaterialEliminatePhantomUnownedAndFlagNeededPlacement() {
        assertEquals("DIRT", CandidateActionContext.observedDropMaterial("GRASS_BLOCK", true, false, false));
        assertEquals("COBBLESTONE", CandidateActionContext.observedDropMaterial("STONE", true, false, false));
        assertNull(CandidateActionContext.observedDropMaterial("IRON_ORE", false, false, false));
        assertEquals("RAW_IRON", CandidateActionContext.observedDropMaterial("IRON_ORE", true, false, false));
        assertNull(CandidateActionContext.observedDropMaterial("OAK_LEAVES", true, false, false));
        assertEquals("OAK_LEAVES", CandidateActionContext.observedDropMaterial("OAK_LEAVES", true, false, true));
        assertNull(CandidateActionContext.observedDropMaterial("SHORT_GRASS", true, false, false));
        assertEquals("GRASS_BLOCK", CandidateActionContext.observedDropMaterial("GRASS_BLOCK", true, true, false));
        assertEquals("OAK_LOG", CandidateActionContext.observedDropMaterial("OAK_LOG", true, false, false));

        var emptyCtx = new CandidateActionContext.Context(Map.of(), null, false, Set.of());
        double[] tblFeat = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(tblFeat, PrimitiveType.PLACE_BLOCK, "CRAFTING_TABLE", null, emptyCtx);
        assertEquals(1.0, tblFeat[CandidateActionContext.MATERIAL_IS_WORKSTATION]);

        double[] btnFeat = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(btnFeat, PrimitiveType.CRAFT_ITEM, "OAK_BUTTON", null, emptyCtx);
        assertEquals(0.0, btnFeat[CandidateActionContext.MATERIAL_IS_WORKSTATION]);

        double[] neededPlace = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeWorkstationContext(neededPlace, PrimitiveType.PLACE_BLOCK, "CRAFTING_TABLE",
                false, true, false, false);
        assertEquals(1.0, neededPlace[CandidateActionContext.PLACES_NEEDED_WORKSTATION]);
        assertEquals(0.0, neededPlace[CandidateActionContext.PLACES_REDUNDANT_WORKSTATION]);

        double[] redundantPlace = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeWorkstationContext(redundantPlace, PrimitiveType.PLACE_BLOCK, "CRAFTING_TABLE",
                true, true, false, false);
        assertEquals(0.0, redundantPlace[CandidateActionContext.PLACES_NEEDED_WORKSTATION]);
        assertEquals(1.0, redundantPlace[CandidateActionContext.PLACES_REDUNDANT_WORKSTATION]);

        double[] redundantCraft = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeWorkstationContext(redundantCraft, PrimitiveType.CRAFT_ITEM, "FURNACE",
                false, false, false, true);
        assertEquals(1.0, redundantCraft[CandidateActionContext.CRAFTS_REDUNDANT_WORKSTATION]);
    }

    @Test void recipePreparationStockIncludesOffhandAndWornItems() {
        UUID world = UUID.randomUUID();
        var storageOnlySlot = List.of(new me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot.ItemSlot(
                0, "OAK_PLANKS", 2, 0, 0, Map.of()));
        var snapshot = new me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot(
                1, 10, world, new me.herry.minecraftAI.ai.primitive.PrimitiveTarget.Point(0, 64, 0),
                20.0, 20, 300, true, false, false, storageOnlySlot);
        var body = new me.herry.minecraftAI.ai.observation.SurvivalObservation.Body(20, 20, 0, 20, 0, 0, 300, 300);
        // Simulate 1 OAK_PLANKS + 2 STICK held in off-hand slot 40: observed.stock() includes them while snapshot < 36 does not.
        Map<String, Integer> fullOwnedStock = Map.of("OAK_PLANKS", 3, "STICK", 2);
        var observed = new PolicyCandidates.Observed(snapshot, body, fullOwnedStock, new double[PolicyFrame.STATE_SIZE]);
        assertEquals(fullOwnedStock, RecipePreparation.preparationStock(observed));
    }

    @Test void craftUtilityEncodesHarvestTierUpgradesDuplicateDurablesAndDeadEnds() {
        var woodenPick = new CandidateActionContext.MaterialProperties(
                0, true, true, false, 2.0, false, false, false, false, false);
        var stonePick = new CandidateActionContext.MaterialProperties(
                0, true, true, true, 4.0, false, false, false, false, false);
        var ironPick = new CandidateActionContext.MaterialProperties(
                0, true, true, true, 6.0, true, false, false, false, false);
        var furnace = new CandidateActionContext.MaterialProperties(
                1, false, false, false, 1.0, false, false, true, false, false);
        var stick = new CandidateActionContext.MaterialProperties(
                16, false, false, false, 1.0, false, false, false, false, true);
        var cobbleSlab = new CandidateActionContext.MaterialProperties(
                0, false, false, false, 1.0, false, false, true, false, false);

        double[] emptyWooden = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialProperties(emptyWooden, woodenPick);
        CandidateActionContext.encodeCraftUtility(emptyWooden, PrimitiveType.CRAFT_ITEM, "WOODEN_PICKAXE",
                woodenPick, CandidateActionContext.HarvestCapabilities.NONE, 0);
        assertEquals(0.0, emptyWooden[CandidateActionContext.MATERIAL_HARVESTS_DIAMOND_ORE]);
        assertEquals(1.0, emptyWooden[CandidateActionContext.CRAFT_UPGRADES_HARVEST_TIER]);
        assertEquals(0.0, emptyWooden[CandidateActionContext.CRAFT_DUPLICATES_OWNED_DURABLE]);
        assertEquals(0.0, emptyWooden[CandidateActionContext.MATERIAL_CRAFTING_DEAD_END]);

        var ownsWoodCaps = new CandidateActionContext.HarvestCapabilities(true, false, false, false);
        double[] dupWooden = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeCraftUtility(dupWooden, PrimitiveType.CRAFT_ITEM, "WOODEN_PICKAXE",
                woodenPick, ownsWoodCaps, 1);
        assertEquals(0.0, dupWooden[CandidateActionContext.CRAFT_UPGRADES_HARVEST_TIER]);
        assertEquals(1.0, dupWooden[CandidateActionContext.CRAFT_DUPLICATES_OWNED_DURABLE]);
        assertEquals(0.0, dupWooden[CandidateActionContext.MATERIAL_CRAFTING_DEAD_END]);

        double[] upStone = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeCraftUtility(upStone, PrimitiveType.CRAFT_ITEM, "STONE_PICKAXE",
                stonePick, ownsWoodCaps, 0);
        assertEquals(1.0, upStone[CandidateActionContext.CRAFT_UPGRADES_HARVEST_TIER]);
        assertEquals(0.0, upStone[CandidateActionContext.CRAFT_DUPLICATES_OWNED_DURABLE]);

        var ownsStoneCaps = new CandidateActionContext.HarvestCapabilities(true, true, false, false);
        double[] upIron = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMaterialProperties(upIron, ironPick);
        CandidateActionContext.encodeCraftUtility(upIron, PrimitiveType.CRAFT_ITEM, "IRON_PICKAXE",
                ironPick, ownsStoneCaps, 0);
        assertEquals(1.0, upIron[CandidateActionContext.MATERIAL_HARVESTS_DIAMOND_ORE]);
        assertEquals(1.0, upIron[CandidateActionContext.CRAFT_UPGRADES_HARVEST_TIER]);
        assertEquals(0.0, upIron[CandidateActionContext.CRAFT_DUPLICATES_OWNED_DURABLE]);

        double[] furnFeat = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeCraftUtility(furnFeat, PrimitiveType.CRAFT_ITEM, "FURNACE",
                furnace, ownsWoodCaps, 0);
        assertEquals(0.0, furnFeat[CandidateActionContext.CRAFT_UPGRADES_HARVEST_TIER]);
        assertEquals(0.0, furnFeat[CandidateActionContext.CRAFT_DUPLICATES_OWNED_DURABLE]);
        assertEquals(0.0, furnFeat[CandidateActionContext.MATERIAL_CRAFTING_DEAD_END]);

        double[] stickFeat = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeCraftUtility(stickFeat, PrimitiveType.CRAFT_ITEM, "STICK",
                stick, ownsWoodCaps, 0);
        assertEquals(0.0, stickFeat[CandidateActionContext.MATERIAL_CRAFTING_DEAD_END]);

        double[] slabFeat = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeCraftUtility(slabFeat, PrimitiveType.CRAFT_ITEM, "COBBLESTONE_SLAB",
                cobbleSlab, ownsWoodCaps, 0);
        assertEquals(1.0, slabFeat[CandidateActionContext.MATERIAL_CRAFTING_DEAD_END]);
    }

    @Test void reachableWorkstationClearsPhantomUnownedAndFlagsBreaking() {
        var ctx = new CandidateActionContext.Context(Map.of(), null, false, Set.of());

        double[] breakReachableTable = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(breakReachableTable, PrimitiveType.BREAK_BLOCK, "CRAFTING_TABLE", null, ctx);
        assertEquals(1.0, breakReachableTable[CandidateActionContext.UNOWNED_MATERIAL]);
        CandidateActionContext.encodeWorkstationContext(breakReachableTable, PrimitiveType.BREAK_BLOCK, "CRAFTING_TABLE",
                true, false, false, false);
        assertEquals(1.0, breakReachableTable[CandidateActionContext.BREAKS_REACHABLE_WORKSTATION]);
        assertEquals(0.0, breakReachableTable[CandidateActionContext.UNOWNED_MATERIAL]);

        double[] craftRedundantTable = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(craftRedundantTable, PrimitiveType.CRAFT_ITEM, "CRAFTING_TABLE", null, ctx);
        assertEquals(1.0, craftRedundantTable[CandidateActionContext.UNOWNED_MATERIAL]);
        CandidateActionContext.encodeWorkstationContext(craftRedundantTable, PrimitiveType.CRAFT_ITEM, "CRAFTING_TABLE",
                true, false, false, false);
        assertEquals(1.0, craftRedundantTable[CandidateActionContext.CRAFTS_REDUNDANT_WORKSTATION]);
        assertEquals(0.0, craftRedundantTable[CandidateActionContext.UNOWNED_MATERIAL]);

        double[] craftNeededTable = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(craftNeededTable, PrimitiveType.CRAFT_ITEM, "CRAFTING_TABLE", null, ctx);
        CandidateActionContext.encodeWorkstationContext(craftNeededTable, PrimitiveType.CRAFT_ITEM, "CRAFTING_TABLE",
                false, false, false, false);
        assertEquals(0.0, craftNeededTable[CandidateActionContext.CRAFTS_REDUNDANT_WORKSTATION]);
        assertEquals(1.0, craftNeededTable[CandidateActionContext.UNOWNED_MATERIAL]);

        double[] breakReachableFurnace = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encode(breakReachableFurnace, PrimitiveType.BREAK_BLOCK, "FURNACE", null, ctx);
        CandidateActionContext.encodeWorkstationContext(breakReachableFurnace, PrimitiveType.BREAK_BLOCK, "FURNACE",
                false, false, true, false);
        assertEquals(1.0, breakReachableFurnace[CandidateActionContext.BREAKS_REACHABLE_WORKSTATION]);
        assertEquals(0.0, breakReachableFurnace[CandidateActionContext.UNOWNED_MATERIAL]);
    }

    @Test void miningPhysicsSeparatesToolGatedBlocksFromBareHandBlocks() {
        double[] dirtOrLog = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMiningPhysics(dirtOrLog, true, false);
        assertEquals(1.0, dirtOrLog[CandidateActionContext.BREAK_HARVESTS_DROPS]);
        assertEquals(0.0, dirtOrLog[CandidateActionContext.BREAK_REQUIRES_CORRECT_TOOL]);

        double[] stoneWithPickaxe = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMiningPhysics(stoneWithPickaxe, true, true);
        assertEquals(1.0, stoneWithPickaxe[CandidateActionContext.BREAK_HARVESTS_DROPS]);
        assertEquals(1.0, stoneWithPickaxe[CandidateActionContext.BREAK_REQUIRES_CORRECT_TOOL]);

        double[] stoneWithHand = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMiningPhysics(stoneWithHand, false, true);
        assertEquals(0.0, stoneWithHand[CandidateActionContext.BREAK_HARVESTS_DROPS]);
        assertEquals(1.0, stoneWithHand[CandidateActionContext.BREAK_REQUIRES_CORRECT_TOOL]);
    }

    @Test void nonDroppingBlockPreservesBlockIdentityWithoutPhantomUnownedAndFlagsBelowFeet() {
        double[] leaves = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMiningPhysics(leaves, false, false, false);
        assertEquals(0.0, leaves[CandidateActionContext.BREAK_HARVESTS_DROPS]);
        assertEquals(0.0, leaves[CandidateActionContext.BREAK_REQUIRES_CORRECT_TOOL]);
        assertEquals(0.0, leaves[CandidateActionContext.BREAK_BELOW_FEET]);

        var ctx = new CandidateActionContext.Context(Map.of(), null, false, Set.of());
        CandidateActionContext.encode(leaves, PrimitiveType.BREAK_BLOCK, null, null, ctx);
        CandidateActionContext.encodeMaterialProperties(leaves, CandidateActionContext.MaterialProperties.EMPTY);
        CandidateActionContext.encodeCraftUtility(leaves, PrimitiveType.BREAK_BLOCK, null,
                CandidateActionContext.MaterialProperties.EMPTY, CandidateActionContext.HarvestCapabilities.NONE, 0);
        CandidateActionContext.encodeNonDroppingBlock(leaves, "OAK_LEAVES");

        assertEquals(1.0, leaves[CandidateActionContext.HAS_MATERIAL]);
        assertEquals(1.0, leaves[CandidateActionContext.MATERIAL_IS_BLOCK]);
        assertEquals(1.0, leaves[CandidateActionContext.MATERIAL_CRAFTING_DEAD_END]);
        assertEquals(0.0, leaves[CandidateActionContext.MATERIAL_IS_WORKSTATION]);
        assertEquals(1.0, leaves[CandidateActionContext.OFFSET + Math.floorMod("OAK_LEAVES".hashCode(), 16)]);
        assertEquals(0.0, leaves[CandidateActionContext.OWNED_MATERIAL]);
        assertEquals(0.0, leaves[CandidateActionContext.UNOWNED_MATERIAL]);
        assertEquals(0.0, leaves[CandidateActionContext.OBJECTIVE_DIRECT_MATCH]);
        assertEquals(0.0, leaves[CandidateActionContext.OBJECTIVE_INGREDIENT_MATCH]);

        double[] belowDirt = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeMiningPhysics(belowDirt, true, false, true);
        assertEquals(1.0, belowDirt[CandidateActionContext.BREAK_HARVESTS_DROPS]);
        assertEquals(0.0, belowDirt[CandidateActionContext.BREAK_REQUIRES_CORRECT_TOOL]);
        assertEquals(1.0, belowDirt[CandidateActionContext.BREAK_BELOW_FEET]);

        double[] anvil = new double[PolicyFrame.ACTION_SIZE];
        CandidateActionContext.encodeNonDroppingBlock(anvil, "ANVIL");
        assertEquals(1.0, anvil[CandidateActionContext.MATERIAL_IS_WORKSTATION]);

        assertEquals("RAW_IRON", CandidateActionContext.observedDropMaterial("IRON_ORE", true, false, false));
        assertNull(CandidateActionContext.observedDropMaterial("OAK_LEAVES", true, false, false));
    }
}
