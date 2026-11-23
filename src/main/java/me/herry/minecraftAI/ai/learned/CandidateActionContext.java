package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.crafting.CraftingSystem;
import me.herry.minecraftAI.ai.crafting.RecipeOption;
import me.herry.minecraftAI.ai.observation.SurvivalObservation;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure factual candidate-material, recipe-structure, objective-relation and inventory material-impact
 * action observations (indices 256..303). Never encodes hand-written utilities, progression order or item
 * preferences. All objective-relation and material-impact bits are masked to 0 when objective input is masked,
 * world mismatches, or the objective is already achieved.
 */
public final class CandidateActionContext {
    public static final int OFFSET = 256;
    public static final int HAS_MATERIAL = 272;
    public static final int OWNED_MATERIAL = 273;
    public static final int UNOWNED_MATERIAL = 274;
    public static final int OBJECTIVE_DIRECT_MATCH = 275;
    public static final int OBJECTIVE_INGREDIENT_MATCH = 276;
    public static final int CONSUMES_OBJECTIVE_ITEM = 277;
    public static final int RECIPE_NEEDS_TABLE = 278;
    public static final int RECIPE_INGREDIENT_UNITS = 279;
    public static final int RECIPE_INGREDIENT_GROUPS = 280;
    public static final int OBJECTIVE_INDIRECT_INGREDIENT_MATCH = 283;
    public static final int ACQUIRES_REQUIRED_CRAFTING_TABLE = 286;
    public static final int PLACES_REQUIRED_CRAFTING_TABLE = 287;
    public static final int CONSUMES_OBJECTIVE_INGREDIENT = 288;
    public static final int OBJECTIVE_INGREDIENT_CONSUMED_UNITS = 289;
    public static final int OBJECTIVE_RECIPE_COMPLETION_DELTA = 290;
    public static final int ENABLES_OBJECTIVE_RECIPE_FEASIBILITY = 291;
    public static final int BREAKS_OBJECTIVE_RECIPE_FEASIBILITY = 292;
    public static final int DEPLETES_OBJECTIVE_INGREDIENT_GROUP = 293;
    public static final int CONSUMES_INDIRECT_INGREDIENT = 294;
    public static final int CONSUMES_REQUIRED_TABLE_INGREDIENT = 295;
    public static final int REQUIRED_TABLE_COMPLETION_DELTA = 296;
    public static final int ENABLES_REQUIRED_TABLE_FEASIBILITY = 297;
    public static final int BREAKS_REQUIRED_TABLE_FEASIBILITY = 298;
    public static final int BREAKS_TABLE_AND_OBJECTIVE_FEASIBILITY = 299;
    public static final int ENABLES_TABLE_AND_OBJECTIVE_FEASIBILITY = 300;
    public static final int MATERIAL_DEPENDENT_RECIPES = 18;
    public static final int MATERIAL_HAS_DURABILITY = 19;
    public static final int MATERIAL_HARVESTS_STONE = 20;
    public static final int MATERIAL_HARVESTS_IRON_ORE = 21;
    public static final int MATERIAL_STONE_DESTROY_SPEED = 22;
    public static final int MATERIAL_HARVESTS_OBSIDIAN = 23;
    public static final int MATERIAL_IS_BLOCK = 301;
    public static final int MATERIAL_IS_FOOD = 302;
    public static final int MATERIAL_IS_FUEL = 303;
    public static final int BREAK_HARVESTS_DROPS = 240;
    public static final int MATERIAL_IS_WORKSTATION = 241;
    public static final int PLACES_NEEDED_WORKSTATION = 242;
    public static final int CRAFTS_REDUNDANT_WORKSTATION = 243;
    public static final int PLACES_REDUNDANT_WORKSTATION = 244;
    private static final int IMPACT_START = CONSUMES_OBJECTIVE_INGREDIENT;
    private static final int IMPACT_END = ENABLES_TABLE_AND_OBJECTIVE_FEASIBILITY;
    private static final int ALLOCATION_SEARCH_LIMIT = 4096;
    private static final Set<String> WORKSTATIONS = Set.of(
            "CRAFTING_TABLE", "FURNACE", "BLAST_FURNACE", "SMOKER",
            "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "ENCHANTING_TABLE",
            "BREWING_STAND", "SMITHING_TABLE", "STONECUTTER", "LOOM",
            "GRINDSTONE", "CARTOGRAPHY_TABLE");

    public static boolean isWorkstationMaterial(String material) {
        return material != null && WORKSTATIONS.contains(material);
    }

    public record SlotAmount(int slot, String material, int amount) {}

    public record MaterialDelta(Map<String, Integer> consumed, Map<String, Integer> produced) {
        public static final MaterialDelta EMPTY = new MaterialDelta(Map.of(), Map.of());

        public MaterialDelta {
            consumed = consumed == null ? Map.of() : Map.copyOf(consumed);
            produced = produced == null ? Map.of() : Map.copyOf(produced);
        }

        public boolean isEmpty() {
            return consumed.isEmpty() && produced.isEmpty();
        }
    }

    public record Context(Map<String, Integer> stock, PolicyTaskMemory.View task, boolean objectiveVisible,
                          Set<String> objectiveIngredients, Set<String> indirectIngredients, boolean requiresCraftingTable,
                          Map<String, Integer> storageStock, List<SlotAmount> storageSlots,
                          List<RecipeOption> objectiveRecipes, List<RecipeOption> tableRecipes) {
        public Context(Map<String, Integer> stock, PolicyTaskMemory.View task, boolean objectiveVisible,
                       Set<String> objectiveIngredients) {
            this(stock, task, objectiveVisible, objectiveIngredients, Set.of(), false,
                    stock, List.of(), List.of(), List.of());
        }

        public Context(Map<String, Integer> stock, PolicyTaskMemory.View task, boolean objectiveVisible,
                       Set<String> objectiveIngredients, Set<String> indirectIngredients, boolean requiresCraftingTable) {
            this(stock, task, objectiveVisible, objectiveIngredients, indirectIngredients, requiresCraftingTable,
                    stock, List.of(), List.of(), List.of());
        }

        public Context {
            stock = stock == null ? Map.of() : Map.copyOf(stock);
            objectiveIngredients = objectiveIngredients == null ? Set.of() : Set.copyOf(objectiveIngredients);
            indirectIngredients = indirectIngredients == null ? Set.of() : Set.copyOf(indirectIngredients);
            storageStock = storageStock == null ? stock : Map.copyOf(storageStock);
            storageSlots = storageSlots == null ? List.of() : List.copyOf(storageSlots);
            objectiveRecipes = objectiveRecipes == null ? List.of() : List.copyOf(objectiveRecipes);
            tableRecipes = tableRecipes == null ? List.of() : List.copyOf(tableRecipes);
        }
    }

    private CraftingSystem cachedCrafting;
    private long cachedRevision;
    private Material cachedTarget;
    private Set<String> direct = Set.of();
    private Set<String> indirect = Set.of();
    private List<RecipeOption> objectiveRecipes = List.of();
    private List<RecipeOption> tableRecipes = List.of();
    private boolean requiresCraftingTable;

    Context capture(CraftingSystem crafting, Map<String, Integer> stock, PolicyTaskMemory.View task, boolean objectiveVisible) {
        return capture(crafting, stock, stock, List.of(), task, objectiveVisible);
    }

    Context capture(CraftingSystem crafting, Map<String, Integer> stock, Map<String, Integer> storageStock,
                    List<SlotAmount> storageSlots, PolicyTaskMemory.View task, boolean objectiveVisible) {
        Material target = objectiveVisible && task != null && task.objective() != null
                && task.worldMatches() && !task.currentlyAchieved() ? Material.getMaterial(task.objective().goal().item()) : null;
        if (target == null) {
            return new Context(stock, task, objectiveVisible, Set.of(), Set.of(), false,
                    storageStock, storageSlots, List.of(), List.of());
        }
        if (crafting != cachedCrafting || crafting.revision() != cachedRevision || target != cachedTarget) {
            var supportedRecipes = crafting.recipeOptions(target).stream()
                    .filter(recipe -> !recipe.smelting() && recipe.countPlannable()).toList();
            requiresCraftingTable = !supportedRecipes.isEmpty() && supportedRecipes.stream().allMatch(RecipeOption::needsTable);
            objectiveRecipes = List.copyOf(supportedRecipes);
            tableRecipes = requiresCraftingTable
                    ? crafting.recipeOptions(Material.CRAFTING_TABLE).stream()
                            .filter(recipe -> !recipe.smelting() && recipe.countPlannable()).toList()
                    : List.of();
            direct = Set.copyOf(ingredients(crafting, target));
            Set<String> second = new HashSet<>();
            for (String material : direct) {
                Material intermediate = Material.getMaterial(material);
                if (intermediate != null && intermediate != target) second.addAll(ingredients(crafting, intermediate));
            }
            second.removeAll(direct);
            second.remove(target.name());
            indirect = Set.copyOf(second);
            cachedTarget = target;
            cachedCrafting = crafting;
            cachedRevision = crafting.revision();
        }
        return new Context(stock, task, objectiveVisible, direct, indirect, requiresCraftingTable,
                storageStock, storageSlots, objectiveRecipes, tableRecipes);
    }

    private static Set<String> ingredients(CraftingSystem crafting, Material target) {
        Set<String> ingredients = new HashSet<>();
        for (RecipeOption option : crafting.recipeOptions(target)) {
            if (!option.countPlannable() || option.smelting()) continue;
            for (RecipeOption.Ingredient ingredient : option.ingredients()) ingredients.addAll(ingredient.alternatives());
        }
        for (RecipeOption option : crafting.smeltingOptions(target)) {
            if (!option.countPlannable()) continue;
            for (RecipeOption.Ingredient ingredient : option.ingredients()) ingredients.addAll(ingredient.alternatives());
        }
        return ingredients;
    }

    static void encode(double[] features, PrimitiveType type, String directMaterial, RecipeOption recipe, Context context) {
        encode(features, type, directMaterial, recipe, context, false);
    }

    static void encode(double[] features, PrimitiveType type, String directMaterial, RecipeOption recipe, Context context,
                       boolean takesObservedItem) {
        if (features.length != PolicyFrame.ACTION_SIZE) throw new IllegalArgumentException("candidate action context shape");
        boolean hasMaterial = directMaterial != null && !directMaterial.isBlank();
        if (hasMaterial) {
            features[OFFSET + Math.floorMod(directMaterial.hashCode(), 16)] = 1;
            features[HAS_MATERIAL] = 1;
            features[MATERIAL_IS_WORKSTATION] = isWorkstationMaterial(directMaterial) ? 1 : 0;
            int owned = Math.max(0, context.stock().getOrDefault(directMaterial, 0));
            features[OWNED_MATERIAL] = Math.min(16, owned / 64.0);
            features[UNOWNED_MATERIAL] = owned == 0 ? 1 : 0;
        }
        var task = context.task();
        if (context.objectiveVisible() && task != null && task.objective() != null && task.worldMatches()) {
            String goalItem = task.objective().goal().item();
            boolean acquires = type == PrimitiveType.CRAFT_ITEM || type == PrimitiveType.BREAK_BLOCK || takesObservedItem;
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

    public record MaterialProperties(int dependentRecipes, boolean hasDurability, boolean harvestsStone,
                                     boolean harvestsIronOre, double stoneDestroySpeed, boolean harvestsObsidian,
                                     boolean block, boolean food, boolean fuel) {
        public static final MaterialProperties EMPTY =
                new MaterialProperties(0, false, false, false, 0.0, false, false, false, false);
    }

    public static void encodeMaterialProperties(double[] features, MaterialProperties props) {
        if (features.length != PolicyFrame.ACTION_SIZE) throw new IllegalArgumentException("candidate action context shape");
        if (props == null) return;
        features[MATERIAL_DEPENDENT_RECIPES] = Math.clamp(props.dependentRecipes() / 16.0, 0, 1);
        features[MATERIAL_HAS_DURABILITY] = props.hasDurability() ? 1 : 0;
        features[MATERIAL_HARVESTS_STONE] = props.harvestsStone() ? 1 : 0;
        features[MATERIAL_HARVESTS_IRON_ORE] = props.harvestsIronOre() ? 1 : 0;
        features[MATERIAL_STONE_DESTROY_SPEED] = Math.clamp(props.stoneDestroySpeed() / 16.0, 0, 1);
        features[MATERIAL_HARVESTS_OBSIDIAN] = props.harvestsObsidian() ? 1 : 0;
        features[MATERIAL_IS_BLOCK] = props.block() ? 1 : 0;
        features[MATERIAL_IS_FOOD] = props.food() ? 1 : 0;
        features[MATERIAL_IS_FUEL] = props.fuel() ? 1 : 0;
    }

    public static void encodeWorkstationContext(double[] features, PrimitiveType type, String material,
                                                boolean tableReachable, boolean ownsTable,
                                                boolean furnaceReachable, boolean ownsFurnace) {
        if (features.length != PolicyFrame.ACTION_SIZE) throw new IllegalArgumentException("candidate action context shape");
        features[PLACES_NEEDED_WORKSTATION] = 0;
        features[CRAFTS_REDUNDANT_WORKSTATION] = 0;
        features[PLACES_REDUNDANT_WORKSTATION] = 0;
        if ("CRAFTING_TABLE".equals(material)) {
            if (type == PrimitiveType.PLACE_BLOCK) {
                features[PLACES_NEEDED_WORKSTATION] = tableReachable ? 0 : 1;
                features[PLACES_REDUNDANT_WORKSTATION] = tableReachable ? 1 : 0;
            } else if (type == PrimitiveType.CRAFT_ITEM) {
                features[CRAFTS_REDUNDANT_WORKSTATION] = (tableReachable || ownsTable) ? 1 : 0;
            }
        } else if ("FURNACE".equals(material)) {
            if (type == PrimitiveType.PLACE_BLOCK) {
                features[PLACES_NEEDED_WORKSTATION] = furnaceReachable ? 0 : 1;
                features[PLACES_REDUNDANT_WORKSTATION] = furnaceReachable ? 1 : 0;
            } else if (type == PrimitiveType.CRAFT_ITEM) {
                features[CRAFTS_REDUNDANT_WORKSTATION] = (furnaceReachable || ownsFurnace) ? 1 : 0;
            }
        }
    }

    public static String observedDropMaterial(String blockMaterial, boolean preferredTool, boolean silkTouch, boolean shears) {
        if (blockMaterial == null || blockMaterial.isBlank() || !preferredTool) return null;
        if (silkTouch) return blockMaterial;
        if (!shears && (blockMaterial.endsWith("_LEAVES") || switch (blockMaterial) {
            case "SHORT_GRASS", "TALL_GRASS", "SHORT_DRY_GRASS", "TALL_DRY_GRASS",
                 "FERN", "LARGE_FERN", "VINE", "BUSH", "DEAD_BUSH",
                 "SEAGRASS", "TALL_SEAGRASS", "COBWEB" -> true;
            default -> false;
        })) {
            return null;
        }
        return switch (blockMaterial) {
            case "GRASS_BLOCK", "MYCELIUM", "PODZOL", "DIRT_PATH" -> "DIRT";
            case "STONE" -> "COBBLESTONE";
            case "DEEPSLATE" -> "COBBLED_DEEPSLATE";
            case "COAL_ORE", "DEEPSLATE_COAL_ORE" -> "COAL";
            case "IRON_ORE", "DEEPSLATE_IRON_ORE" -> "RAW_IRON";
            case "COPPER_ORE", "DEEPSLATE_COPPER_ORE" -> "RAW_COPPER";
            case "GOLD_ORE", "DEEPSLATE_GOLD_ORE" -> "RAW_GOLD";
            case "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE" -> "DIAMOND";
            case "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE" -> "REDSTONE";
            case "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE" -> "LAPIS_LAZULI";
            case "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE" -> "EMERALD";
            case "NETHER_QUARTZ_ORE" -> "QUARTZ";
            case "NETHER_GOLD_ORE" -> "GOLD_NUGGET";
            case "CLAY" -> "CLAY_BALL";
            case "GLOWSTONE" -> "GLOWSTONE_DUST";
            case "SNOW", "SNOW_BLOCK" -> "SNOWBALL";
            default -> blockMaterial;
        };
    }

    /**
     * Computes deterministic inventory material changes for candidates whose consumption/production is defined by
     * registered recipes or exact survival actions. Uses actual storage slot order when provided to match
     * VanillaCraftAction slot allocation.
     */
    public static MaterialDelta materialDelta(PrimitiveType type, RecipeOption recipe, PrimitiveCommand command,
                                              SurvivalObservation.Item consumable, int observedPickupAmount,
                                              Map<String, Integer> storageStock, List<SlotAmount> storageSlots) {
        if (type == PrimitiveType.CRAFT_ITEM && recipe != null && !recipe.smelting() && recipe.countPlannable()) {
            Map<String, Integer> consumed = allocateCraftIngredients(recipe, storageStock, storageSlots);
            if (consumed == null) return MaterialDelta.EMPTY;
            Map<String, Integer> produced = recipe.resultAmount() > 0
                    ? Map.of(recipe.result(), recipe.resultAmount())
                    : Map.of();
            return new MaterialDelta(consumed, produced);
        }
        if (type == PrimitiveType.PLACE_BLOCK && command instanceof PrimitiveCommand.PlaceBlock place
                && place.material() != null && !place.material().isBlank()) {
            if (storageStock.getOrDefault(place.material(), 0) <= 0) return MaterialDelta.EMPTY;
            return new MaterialDelta(Map.of(place.material(), 1), Map.of());
        }
        if (type == PrimitiveType.USE_ITEM && command instanceof PrimitiveCommand.FillBucket fill) {
            if (storageStock.getOrDefault(Material.BUCKET.name(), 0) <= 0) return MaterialDelta.EMPTY;
            String producedBucket = "LAVA".equals(fill.fluid()) ? Material.LAVA_BUCKET.name() : Material.WATER_BUCKET.name();
            return new MaterialDelta(Map.of(Material.BUCKET.name(), 1), Map.of(producedBucket, 1));
        }
        if (type == PrimitiveType.USE_ITEM && command instanceof PrimitiveCommand.PourBucket pour) {
            if (storageStock.getOrDefault(pour.bucket(), 0) <= 0) return MaterialDelta.EMPTY;
            return new MaterialDelta(Map.of(pour.bucket(), 1), Map.of(Material.BUCKET.name(), 1));
        }
        if (type == PrimitiveType.USE_ITEM && consumable != null
                && consumable.material() != null && !consumable.material().isBlank()) {
            if (storageStock.getOrDefault(consumable.material(), 0) <= 0) return MaterialDelta.EMPTY;
            String remainder = consumable.consumption().remainderMaterial();
            Map<String, Integer> produced = remainder != null && !remainder.isBlank() ? Map.of(remainder, 1) : Map.of();
            return new MaterialDelta(Map.of(consumable.material(), 1), produced);
        }
        if (type == PrimitiveType.INTERACT_BLOCK && command instanceof PrimitiveCommand.FurnaceTransfer transfer
                && transfer.material() != null && !transfer.material().isBlank()) {
            if (transfer.slot() == PrimitiveCommand.FurnaceSlot.RESULT) {
                return new MaterialDelta(Map.of(), Map.of(transfer.material(), Math.max(1, observedPickupAmount)));
            }
            if (storageStock.getOrDefault(transfer.material(), 0) <= 0) return MaterialDelta.EMPTY;
            return new MaterialDelta(Map.of(transfer.material(), 1), Map.of());
        }
        if (type == PrimitiveType.DROP_ITEM && command instanceof PrimitiveCommand.DropExact drop
                && drop.material() != null && !drop.material().isBlank()) {
            int dropped = 0;
            if (storageSlots != null) {
                for (var s : storageSlots) {
                    if (s.slot() == drop.slot() && drop.material().equals(s.material()) && s.amount() > 0) {
                        dropped = s.amount();
                        break;
                    }
                }
            }
            if (dropped <= 0) dropped = Math.min(64, Math.max(0, storageStock.getOrDefault(drop.material(), 0)));
            return dropped > 0 ? new MaterialDelta(Map.of(drop.material(), dropped), Map.of()) : MaterialDelta.EMPTY;
        }
        if (type == PrimitiveType.PICKUP_ITEM && command instanceof PrimitiveCommand.PickupExact pickup
                && pickup.material() != null && !pickup.material().isBlank() && observedPickupAmount > 0) {
            return new MaterialDelta(Map.of(), Map.of(pickup.material(), observedPickupAmount));
        }
        return MaterialDelta.EMPTY;
    }

    public static Map<String, Integer> applyDelta(Map<String, Integer> stock, MaterialDelta delta) {
        Map<String, Integer> next = new HashMap<>();
        if (stock != null) {
            for (var entry : stock.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) next.put(entry.getKey(), entry.getValue());
            }
        }
        for (var entry : delta.consumed().entrySet()) {
            int remaining = next.getOrDefault(entry.getKey(), 0) - entry.getValue();
            if (remaining > 0) next.put(entry.getKey(), remaining);
            else next.remove(entry.getKey());
        }
        for (var entry : delta.produced().entrySet()) {
            if (entry.getValue() > 0) next.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        return Map.copyOf(next);
    }

    public static void encodeMaterialImpact(double[] features, MaterialDelta delta, Context context, boolean tableKnownMissing) {
        encodeMaterialImpact(features, delta, context, tableKnownMissing, null);
    }

    /**
     * Encodes pure factual material-impact features (indices 288..300) for the given candidate delta against
     * registered objective recipes and (when missing) required crafting-table recipes.
     * {@code smeltingOutput} is non-null only when a furnace ingredient transfer feeds a registered smelting recipe
     * producing that material, so smelting an objective precursor is not flagged as wasteful consumption.
     */
    public static void encodeMaterialImpact(double[] features, MaterialDelta delta, Context context, boolean tableKnownMissing,
                                            String smeltingOutput) {
        if (features.length != PolicyFrame.ACTION_SIZE) throw new IllegalArgumentException("candidate action context shape");
        for (int i = IMPACT_START; i <= IMPACT_END; i++) features[i] = 0;
        var task = context.task();
        if (!context.objectiveVisible() || task == null || task.objective() == null
                || !task.worldMatches() || task.currentlyAchieved() || delta == null || delta.isEmpty()) {
            return;
        }
        Map<String, Integer> before = context.storageStock();
        Map<String, Integer> after = applyDelta(before, delta);
        String goalItem = task.objective().goal().item();
        boolean producesGoal = delta.produced().getOrDefault(goalItem, 0) > 0 || goalItem.equals(smeltingOutput);

        if (!producesGoal) {
            int consumedObjUnits = 0;
            for (var entry : delta.consumed().entrySet()) {
                if (context.objectiveIngredients().contains(entry.getKey())) consumedObjUnits += entry.getValue();
            }
            if (consumedObjUnits > 0) {
                features[CONSUMES_OBJECTIVE_INGREDIENT] = 1;
                features[OBJECTIVE_INGREDIENT_CONSUMED_UNITS] = Math.min(16, consumedObjUnits / 9.0);
            }
            if (!context.objectiveRecipes().isEmpty()) {
                double compBefore = bestRecipeCompletion(context.objectiveRecipes(), before);
                double compAfter = bestRecipeCompletion(context.objectiveRecipes(), after);
                features[OBJECTIVE_RECIPE_COMPLETION_DELTA] = Math.clamp(compAfter - compBefore, -1, 1);
                boolean feasBefore = anyRecipeFeasible(context.objectiveRecipes(), before);
                boolean feasAfter = anyRecipeFeasible(context.objectiveRecipes(), after);
                if (!feasBefore && feasAfter) features[ENABLES_OBJECTIVE_RECIPE_FEASIBILITY] = 1;
                if (feasBefore && !feasAfter) features[BREAKS_OBJECTIVE_RECIPE_FEASIBILITY] = 1;
                if (anyRecipeGroupDepleted(context.objectiveRecipes(), before, after)) {
                    features[DEPLETES_OBJECTIVE_INGREDIENT_GROUP] = 1;
                }
            }
        }

        boolean producesGoalOrDirect = producesGoal
                || (smeltingOutput != null && context.objectiveIngredients().contains(smeltingOutput))
                || delta.produced().keySet().stream().anyMatch(context.objectiveIngredients()::contains);
        if (!producesGoalOrDirect
                && delta.consumed().keySet().stream().anyMatch(context.indirectIngredients()::contains)) {
            features[CONSUMES_INDIRECT_INGREDIENT] = 1;
        }

        String tableName = Material.CRAFTING_TABLE.name();
        if (context.requiresCraftingTable() && tableKnownMissing
                && context.stock().getOrDefault(tableName, 0) == 0 && !context.tableRecipes().isEmpty()) {
            boolean producesTable = delta.produced().getOrDefault(tableName, 0) > 0;
            if (!producesTable) {
                Set<String> tableIngredients = new HashSet<>();
                for (var option : context.tableRecipes()) {
                    for (var group : option.ingredients()) tableIngredients.addAll(group.alternatives());
                }
                if (delta.consumed().keySet().stream().anyMatch(tableIngredients::contains)) {
                    features[CONSUMES_REQUIRED_TABLE_INGREDIENT] = 1;
                }
                double tBefore = bestRecipeCompletion(context.tableRecipes(), before);
                double tAfter = bestRecipeCompletion(context.tableRecipes(), after);
                features[REQUIRED_TABLE_COMPLETION_DELTA] = Math.clamp(tAfter - tBefore, -1, 1);
                boolean tFeasBefore = anyRecipeFeasible(context.tableRecipes(), before);
                boolean tFeasAfter = anyRecipeFeasible(context.tableRecipes(), after);
                if (!tFeasBefore && tFeasAfter) features[ENABLES_REQUIRED_TABLE_FEASIBILITY] = 1;
                if (tFeasBefore && !tFeasAfter) features[BREAKS_REQUIRED_TABLE_FEASIBILITY] = 1;
                if (!producesGoal && !context.objectiveRecipes().isEmpty()) {
                    boolean combBefore = anyCombinedFeasible(context.tableRecipes(), context.objectiveRecipes(), before);
                    boolean combAfter = anyCombinedFeasible(context.tableRecipes(), context.objectiveRecipes(), after);
                    if (combBefore && !combAfter) features[BREAKS_TABLE_AND_OBJECTIVE_FEASIBILITY] = 1;
                    if (!combBefore && combAfter) features[ENABLES_TABLE_AND_OBJECTIVE_FEASIBILITY] = 1;
                }
            }
        }
    }

    static double bestRecipeCompletion(List<RecipeOption> recipes, Map<String, Integer> stock) {
        double best = 0;
        for (var recipe : recipes) best = Math.max(best, recipeCompletion(recipe, stock));
        return best;
    }

    static double recipeCompletion(RecipeOption recipe, Map<String, Integer> stock) {
        int totalNeeded = 0;
        int sumSatisfied = 0;
        Set<String> union = new HashSet<>();
        for (var group : recipe.ingredients()) {
            int available = 0;
            for (String alt : group.alternatives()) {
                available += Math.max(0, stock.getOrDefault(alt, 0));
                union.add(alt);
            }
            totalNeeded += group.amount();
            sumSatisfied += Math.min(group.amount(), available);
        }
        if (totalNeeded <= 0) return 0;
        int unionAvailable = 0;
        for (String alt : union) unionAvailable += Math.max(0, stock.getOrDefault(alt, 0));
        return Math.min(sumSatisfied, unionAvailable) / (double) totalNeeded;
    }

    static boolean anyRecipeFeasible(List<RecipeOption> recipes, Map<String, Integer> stock) {
        for (var recipe : recipes) {
            if (isRecipeExactFeasible(recipe.ingredients(), stock)) return true;
        }
        return false;
    }

    static boolean anyRecipeGroupDepleted(List<RecipeOption> recipes, Map<String, Integer> before, Map<String, Integer> after) {
        for (var recipe : recipes) {
            for (var group : recipe.ingredients()) {
                int availBefore = 0, availAfter = 0;
                for (String alt : group.alternatives()) {
                    availBefore += Math.max(0, before.getOrDefault(alt, 0));
                    availAfter += Math.max(0, after.getOrDefault(alt, 0));
                }
                if (availBefore >= group.amount() && availAfter < group.amount()) return true;
            }
        }
        return false;
    }

    static boolean anyCombinedFeasible(List<RecipeOption> firstRecipes, List<RecipeOption> secondRecipes,
                                       Map<String, Integer> stock) {
        for (var first : firstRecipes) {
            for (var second : secondRecipes) {
                List<RecipeOption.Ingredient> merged = mergeGroups(first.ingredients(), second.ingredients());
                if (isRecipeExactFeasible(merged, stock)) return true;
            }
        }
        return false;
    }

    private static List<RecipeOption.Ingredient> mergeGroups(List<RecipeOption.Ingredient> first,
                                                             List<RecipeOption.Ingredient> second) {
        Map<List<String>, Integer> amounts = new LinkedHashMap<>();
        for (var group : first) amounts.merge(group.alternatives(), group.amount(), Integer::sum);
        for (var group : second) amounts.merge(group.alternatives(), group.amount(), Integer::sum);
        List<RecipeOption.Ingredient> result = new ArrayList<>(amounts.size());
        for (var entry : amounts.entrySet()) {
            result.add(new RecipeOption.Ingredient(entry.getValue(), entry.getKey(), false));
        }
        return result;
    }

    private static boolean isRecipeExactFeasible(List<RecipeOption.Ingredient> groups, Map<String, Integer> stock) {
        if (groups.isEmpty()) return false;
        int totalNeeded = 0;
        Set<String> allAlts = new HashSet<>();
        boolean overlap = false;
        for (var group : groups) {
            int available = 0;
            for (String alt : group.alternatives()) {
                available += Math.max(0, stock.getOrDefault(alt, 0));
                if (!allAlts.add(alt)) overlap = true;
            }
            if (available < group.amount()) return false;
            totalNeeded += group.amount();
        }
        int unionAvailable = 0;
        for (String alt : allAlts) unionAvailable += Math.max(0, stock.getOrDefault(alt, 0));
        if (unionAvailable < totalNeeded) return false;
        if (!overlap) return true;
        return allocateFromStock(groups, stock) != null;
    }

    private static Map<String, Integer> allocateCraftIngredients(RecipeOption recipe, Map<String, Integer> storageStock,
                                                                 List<SlotAmount> storageSlots) {
        if (!CraftingSystem.hasRequiredIngredients(recipe, storageStock)) return null;
        if (storageSlots != null && !storageSlots.isEmpty()) {
            Map<String, Integer> allocated = allocateFromSlots(recipe.ingredients(), storageSlots);
            if (allocated != null) return allocated;
        }
        return allocateFromStock(recipe.ingredients(), storageStock);
    }

    private static Map<String, Integer> allocateFromSlots(List<RecipeOption.Ingredient> groups, List<SlotAmount> storageSlots) {
        List<SlotAmount> sorted = new ArrayList<>(storageSlots);
        sorted.sort(java.util.Comparator.comparingInt(SlotAmount::slot));
        int[] remaining = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) remaining[i] = Math.max(0, sorted.get(i).amount());
        List<Set<String>> units = expandUnits(groups);
        int[] chosen = new int[units.size()];
        int[] budget = {ALLOCATION_SEARCH_LIMIT};
        if (!assignSlots(0, units, sorted, remaining, chosen, budget)) return null;
        Map<String, Integer> consumed = new LinkedHashMap<>();
        for (int slotIndex : chosen) consumed.merge(sorted.get(slotIndex).material(), 1, Integer::sum);
        return consumed;
    }

    private static boolean assignSlots(int unitIndex, List<Set<String>> units, List<SlotAmount> slots,
                                       int[] remaining, int[] chosen, int[] budget) {
        if (--budget[0] < 0) return false;
        if (unitIndex == units.size()) return true;
        Set<String> allowed = units.get(unitIndex);
        for (int i = 0; i < slots.size() && budget[0] >= 0; i++) {
            if (remaining[i] <= 0 || !allowed.contains(slots.get(i).material())) continue;
            remaining[i]--;
            chosen[unitIndex] = i;
            if (assignSlots(unitIndex + 1, units, slots, remaining, chosen, budget)) return true;
            remaining[i]++;
        }
        return false;
    }

    private static Map<String, Integer> allocateFromStock(List<RecipeOption.Ingredient> groups, Map<String, Integer> stock) {
        Map<String, Integer> remaining = new HashMap<>();
        for (var entry : stock.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) remaining.put(entry.getKey(), entry.getValue());
        }
        List<List<String>> units = new ArrayList<>();
        for (var group : groups) {
            for (int i = 0; i < group.amount(); i++) units.add(group.alternatives());
        }
        String[] chosen = new String[units.size()];
        int[] budget = {ALLOCATION_SEARCH_LIMIT};
        if (!assignStock(0, units, remaining, chosen, budget)) return null;
        Map<String, Integer> consumed = new LinkedHashMap<>();
        for (String material : chosen) consumed.merge(material, 1, Integer::sum);
        return consumed;
    }

    private static boolean assignStock(int unitIndex, List<List<String>> units, Map<String, Integer> remaining,
                                       String[] chosen, int[] budget) {
        if (--budget[0] < 0) return false;
        if (unitIndex == units.size()) return true;
        for (String alt : units.get(unitIndex)) {
            int avail = remaining.getOrDefault(alt, 0);
            if (avail <= 0) continue;
            remaining.put(alt, avail - 1);
            chosen[unitIndex] = alt;
            if (assignStock(unitIndex + 1, units, remaining, chosen, budget)) return true;
            remaining.put(alt, avail);
        }
        return false;
    }

    private static List<Set<String>> expandUnits(List<RecipeOption.Ingredient> groups) {
        List<Set<String>> units = new ArrayList<>();
        for (var group : groups) {
            Set<String> set = Set.copyOf(group.alternatives());
            for (int i = 0; i < group.amount(); i++) units.add(set);
        }
        return units;
    }
}
