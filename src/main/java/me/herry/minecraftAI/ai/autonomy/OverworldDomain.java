package me.herry.minecraftAI.ai.autonomy;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.crafting.RecipeOption;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.inventory.ArmorSlot;
import me.herry.minecraftAI.ai.inventory.ArmorTier;
import me.herry.minecraftAI.ai.inventory.ToolTier;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.plan.BuildPlans;
import me.herry.minecraftAI.ai.plan.Progression;
import me.herry.minecraftAI.ai.plan.PortalPlans;
import me.herry.minecraftAI.ai.plan.ForagePlans;
import me.herry.minecraftAI.ai.plan.ResourceOptions;
import me.herry.minecraftAI.ai.plan.FarmingPlans;
import me.herry.minecraftAI.ai.plan.IrrigationPlans;
import me.herry.minecraftAI.ai.plan.SeedGatheringPlans;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.survival.FoodSources;
import me.herry.minecraftAI.ai.farming.CropRules;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Current facts and conditional action knowledge. Simulated effects are never written to the world model. */
public final class OverworldDomain {
    public enum Kind { LEGACY_ACTION, CRAFT, PLACE_FURNACE, SMELT, COLLECT_FURNACE, FETCH, MINE_MATERIAL, SURVEY_COAL, FORAGE_BLOCK,
        PLANT_CROP, INSPECT_CROP, GATHER_SEEDS, IRRIGATE }

    public record Operation(Kind kind, GoalType label, Material target, int amount, boolean exploratory, String fuel, String recipe,
                            BlockPoint position) {
        public Operation(Kind kind, GoalType label, Material target, int amount, boolean exploratory) {
            this(kind, label, target, amount, exploratory, "", "", null);
        }
        public Operation(Kind kind, GoalType label, Material target, int amount, boolean exploratory, String fuel) {
            this(kind, label, target, amount, exploratory, fuel, "", null);
        }
        public Operation(Kind kind, GoalType label, Material target, int amount, boolean exploratory, String fuel, String recipe) {
            this(kind, label, target, amount, exploratory, fuel, recipe, null);
        }
    }
    public record Snapshot(UUID world, PlanningState state, List<ActionModel> actions,
                           Map<String, Operation> operations, List<String> limits) {
        public Snapshot { actions = List.copyOf(actions); operations = Map.copyOf(operations); limits = List.copyOf(limits); }
    }

    private static final List<Material> ROOT_ITEMS = List.of(Material.OAK_PLANKS, Material.STICK, Material.CRAFTING_TABLE,
            Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.FURNACE, Material.IRON_PICKAXE,
            Material.IRON_SWORD, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS,
            Material.IRON_BOOTS, Material.SHIELD, Material.DIAMOND_PICKAXE, Material.BUCKET,
            Material.FLINT_AND_STEEL, Material.TORCH,
            Material.IRON_INGOT, Material.CHARCOAL, Material.BREAD, Material.DRIED_KELP,
            Material.COOKED_COD, Material.COOKED_SALMON, Material.BAKED_POTATO,
            Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN,
            Material.COOKED_MUTTON, Material.COOKED_RABBIT, Material.BEETROOT_SOUP, Material.WOODEN_HOE);
    private static final int MAX_RECIPE_MATERIALS = 72;
    private static final int MAX_RECIPE_VARIANTS = 6;

    private OverworldDomain() {}

    /** Registry traversal resumes across decisions instead of discarding unfinished dependencies at the tick limit. */
    public static final class Catalog {
        private final ArrayDeque<Material> queue = new ArrayDeque<>(ROOT_ITEMS);
        private final Set<Material> visited = EnumSet.noneOf(Material.class);
        private final List<RecipeOption> options = new ArrayList<>();
        private final long revision;

        public Catalog(long revision) { this.revision = revision; }
        public long revision() { return revision; }
        public boolean complete() { return queue.isEmpty() || visited.size() >= MAX_RECIPE_MATERIALS; }
        public boolean limited() { return !queue.isEmpty() && visited.size() >= MAX_RECIPE_MATERIALS; }

        public void advance(AIPlayer ai) {
            int remaining = 2;
            while (!complete() && remaining > 0 && !ai.getWorkBudget().isExhausted()) {
                Material target = queue.removeFirst();
                if (!visited.add(target)) continue;
                remaining--;
                List<RecipeOption> current = new ArrayList<>(ai.getCrafting().recipeOptions(target));
                current.addAll(ai.getCrafting().smeltingOptions(target));
                for (RecipeOption option : current) {
                    if (!option.countPlannable()) continue;
                    options.add(option);
                    for (var group : option.ingredients()) for (String name : group.alternatives()) {
                        Material input = Material.matchMaterial(name);
                        if (input == null || Tag.LOGS.isTagged(input)) continue;
                        queue.addLast(Tag.PLANKS.isTagged(input) ? Material.OAK_PLANKS : input);
                    }
                }
            }
        }
    }

    public static String item(Material material) {
        if (Tag.LOGS.isTagged(material)) return "stock:logs";
        if (Tag.PLANKS.isTagged(material)) return "stock:planks";
        return "item:" + material.name();
    }

    /** Only body/inventory and already computed perception/landmarks are read here. */
    public static PlanningState observe(AIPlayer ai, Situation s) {
        Map<String, Integer> values = new HashMap<>();
        ai.getInventory().snapshotWithEquipment().forEach((material, count) -> values.merge(item(material), count, Integer::sum));
        ToolTier pick = ai.getInventory().bestTier(Tag.ITEMS_PICKAXES);
        for (ToolTier tier : List.of(ToolTier.WOOD, ToolTier.STONE, ToolTier.IRON, ToolTier.DIAMOND)) {
            if (pick.isAtLeast(tier)) values.put("cap:pickaxe:" + tier.name(), 1);
        }
        if (ai.getInventory().bestTier(Tag.ITEMS_SWORDS).isAtLeast(ToolTier.IRON)) values.put("cap:sword:IRON", 1);
        if (ai.getInventory().count(Tag.ITEMS_HOES) > 0) values.put("cap:hoe", 1);
        for (ArmorSlot slot : ArmorSlot.values()) {
            if (ai.getInventory().bestArmorTier(slot).isAtLeast(ArmorTier.IRON)) values.put("cap:armor:IRON:" + slot.name(), 1);
        }
        values.put("stock:food", ai.getInventory().foodCount());
        values.put("stock:raw-food", s.rawFood);
        values.put("stock:ready-food", Math.max(0, ai.getInventory().foodCount() - s.rawFood));
        var home = ai.getWorldModel().homeIn(ai.getWorldId());
        if (home != null && home.isSheltered()) values.put("world:shelter", 1);
        if (Progression.nearbyTable(ai) != null) values.put("world:table", 1);
        if (Progression.nearbyFurnace(ai) != null) {
            values.put("world:furnace", 1);
            if (!s.furnaceBusy) values.put("world:idle-furnace", 1);
        }
        if (!s.underground && !s.surfaceTooLate) values.put("world:surface", 1);
        if (s.knowsTree || s.treeUnfinished) values.put("known:tree", 1);
        if (s.knowsIron) values.put("known:iron", 1);
        if (s.knowsDiamond) values.put("known:diamond", 1);
        if (s.knowsCoal) values.put("known:coal", 1);
        for (Material drop : ResourceOptions.stoneDrops(ai)) values.put("known:stone:" + drop.name(), 1);
        BlockPoint foodPosition = ForagePlans.locate(ai);
        Material foodSource = foodPosition == null ? null : FoodSources.product(Positions.block(ai.getPlayer().getWorld(), foodPosition).getType());
        if (foodSource != null) {
            values.put("known:food:" + foodSource.name(), 1);
            values.put("known:food-position:" + foodSource.name() + ":" + foodPosition.encode(), 1);
        }
        var farms = FarmingPlans.observe(ai);
        Map<String, Integer> farmFacts = new HashMap<>(FarmPlanning.observed(farms));
        // Site knowledge may be cached; living production capacity requires a current visible crop.
        farmFacts.keySet().removeIf(token -> token.equals("world:crop-planted") || token.startsWith("planted:"));
        var world = ai.getPlayer().getWorld();
        var eye = ai.getPlayer().getEyeLocation();
        var opacity = PerceptionSystem.opacityOf(world);
        for (var crop : farms.crops()) {
            if (ai.getWorkBudget().isExhausted()) break;
            BlockPoint plant = crop.soil().offset(0, 1, 0);
            if (!Positions.isLoaded(world, plant) || Positions.block(world, crop.soil()).getType() != Material.FARMLAND
                    || !Visibility.canSeeBlock(opacity, eye.getX(), eye.getY(), eye.getZ(),
                    plant.x(), plant.y(), plant.z())) continue;
            Material propagation = CropRules.seed(Positions.block(world, plant).getType());
            if (propagation == null) continue;
            farmFacts.merge("world:crop-planted", 1, Integer::sum);
            farmFacts.put(FarmPlanning.planted(propagation, crop.soil()), 1);
        }
        values.putAll(farmFacts);
        var irrigation = IrrigationPlans.observe(ai);
        for (var site : irrigation.sites()) {
            if (!ai.getWorkBudget().isExhausted() && IrrigationPlans.validate(ai, site.cell(), false).isEmpty())
                values.put(IrrigationPlanning.site(site.cell()), 1);
        }
        if (!irrigation.complete()) values.put("knowledge:irrigation-incomplete", 1);
        if (irrigation.encounteredUnknown()) values.put("knowledge:irrigation-unknown", 1);
        for (var source : ai.getMemory().all(MemoryType.WATER_SOURCE, ai.getWorldId(), ai.getTicks()).stream().limit(32).toList()) {
            if (ai.getWorkBudget().isExhausted()) break;
            if (source.pos().distance(ai.getPosition()) <= 5 && IrrigationPlans.observedSource(ai, source.pos()))
                values.put(IrrigationPlanning.water(source.pos()), 1);
        }
        for (BlockPoint seedSource : SeedGatheringPlans.candidates(ai)) values.put("known:seeds:" + seedSource.encode(), 1);
        for (var plot : ai.getMemory().all(MemoryType.FARM_PLOT, ai.getWorldId(), ai.getTicks())) {
            values.put("known:farm:" + plot.pos().encode(), 1);
            if (ai.getMemory().contains(MemoryType.FARM_CHECKED, ai.getWorldId(), plot.pos(), ai.getTicks()))
                values.put(FarmPlanning.checked(plot.pos()), 1);
            else if (!ai.getMemory().contains(MemoryType.UNREACHABLE, ai.getWorldId(), plot.pos(), ai.getTicks()))
                values.put(FarmPlanning.due(plot.pos()), 1);
        }
        values.put("world:explored", ai.getWorldModel().exploredCount(ai.getWorldId()));
        values.put("stock:portal-frame", PortalPlans.placedObsidian(ai));
        if (PortalPlans.observedPortalHere(ai)) values.put("world:portal", 1);
        if (s.knowsWater) values.put("known:water", 1);
        if (s.knowsLava || s.obsidianWork) values.put("known:lava", 1);
        if (s.obsidianWork) values.put("world:obsidian-work", 1);
        for (var entry : ai.getWorldModel().getStorage().entrySet()) {
            if (!entry.getKey().world().equals(ai.getWorldId())) continue;
            entry.getValue().forEach((name, count) -> values.merge("remembered:item:" + name, count, Integer::sum));
        }
        if (ai.getBuildJob() != null) values.put("remaining:shelter", BuildPlans.requirements(ai).structuralBlocks());
        var job = ai.getFurnaceJob();
        if (job != null && job.world().equals(ai.getWorldId()) && !job.result().isEmpty() && s.furnaceBusy) {
            values.put(pending(job.food() ? "ready-food" : job.result(), job.count()), 1);
        }
        return new PlanningState(values);
    }

    public static Snapshot capture(AIPlayer ai, Situation s, Catalog catalog, PlanningState state) {
        Map<String, ActionModel> actions = new LinkedHashMap<>();
        Map<String, Operation> operations = new LinkedHashMap<>();
        var builder = new Builder(state, actions, operations);
        boolean surfaceAllowed = !s.underground || !s.surfaceTooLate;
        if (surfaceAllowed) {
            for (var site : IrrigationPlans.observe(ai).sites()) {
                if (state.count(IrrigationPlanning.site(site.cell())) == 0) continue;
                ActionModel install = IrrigationPlanning.install(site);
                builder.add(install.id(), new Operation(Kind.IRRIGATE, GoalType.STOCK_FOOD, Material.WATER_BUCKET,
                        1, false, "", "", site.cell()), install.preconditions(), install.consumed(), install.effects(),
                        install.cost() + site.cell().distance(ai.getPosition()) / 4.0);
            }
            var farming = FarmingPlans.observe(ai);
            for (var site : farming.plantable().stream().limit(64).toList()) {
                ActionModel plant = FarmPlanning.planting(site, FarmPlanning.requiredSeedCount(farming, site.seed()));
                builder.add(plant.id(), new Operation(Kind.PLANT_CROP, GoalType.STOCK_FOOD, site.seed(), 1, false, "", "", site.soil()),
                        plant.preconditions(), plant.consumed(), plant.effects(), plant.cost());
            }
            for (var plot : ai.getMemory().all(MemoryType.FARM_PLOT, ai.getWorldId(), ai.getTicks()).stream().limit(16).toList()) {
                if (state.count(FarmPlanning.due(plot.pos())) == 0) continue;
                builder.add("inspect:farm:" + plot.pos().encode(), new Operation(Kind.INSPECT_CROP, GoalType.STOCK_FOOD, null, 1, true, "", "", plot.pos()),
                        Map.of(FarmPlanning.due(plot.pos()), 1), Map.of(FarmPlanning.due(plot.pos()), 1),
                        Map.of(FarmPlanning.checked(plot.pos()), 1), 5 + plot.pos().distance(ai.getPosition()) / 4.0);
            }
            for (var token : state.quantities().keySet()) {
                if (!token.startsWith("known:seeds:")) continue;
                BlockPoint source = BlockPoint.parse(token.substring("known:seeds:".length()));
                builder.add("gather:seeds:" + source.encode(), new Operation(Kind.GATHER_SEEDS, GoalType.STOCK_FOOD, Material.WHEAT_SEEDS,
                        1, true, "", "", source), Map.of(token, 1), Map.of(token, 1), Map.of("item:WHEAT_SEEDS", 1), 8);
            }
            state.quantities().forEach((token, count) -> {
                if (!token.startsWith("remembered:item:") || count <= 0) return;
                Material material = Material.matchMaterial(token.substring("remembered:item:".length()));
                if (material == null) return;
                int amount = Math.min(count, 16);
                builder.add("fetch:" + material, new Operation(Kind.FETCH, GoalType.FETCH_ITEMS, material, amount, true),
                        Map.of(token, amount), Map.of(token, amount), effects(material, amount), 6 + s.homeDistance / 8.0);
            });
        }

        // Discovery has information effects only. An unknown ore is never counted as an acquired item.
        if (surfaceAllowed) {
            builder.leaf("survey:tree", GoalType.FIND_WOOD, Map.of(), Map.of("known:tree", 1), 35, true);
            builder.leaf("harvest:wood", GoalType.COLLECT_WOOD, Map.of("known:tree", 1), Map.of("stock:logs", 4), 8, false);
            if (!s.foodSearchExhausted || s.preyNearby) {
                builder.leaf("forage", GoalType.STOCK_FOOD, Map.of(), Map.of("stock:food", 2), s.preyNearby ? 12 : 45, true);
            }
            for (var token : state.quantities().keySet()) {
                if (!token.startsWith("known:food-position:")) continue;
                String value = token.substring("known:food-position:".length());
                int split = value.indexOf(':');
                Material product = Material.matchMaterial(value.substring(0, split));
                BlockPoint source = BlockPoint.parse(value.substring(split + 1));
                if (product != null) builder.add("forage:block:" + product + ":" + source.encode(),
                        new Operation(Kind.FORAGE_BLOCK, GoalType.STOCK_FOOD, product, 1, false, "", "", source),
                        Map.of(token, 1), Map.of(token, 1), effects(product, 1), 8);
            }
            builder.leaf("gravel", GoalType.GATHER_FLINT, Map.of(), Map.of("item:FLINT", 1), s.knowsGravel ? 18 : 55, true);
            builder.add("fill:water", new Operation(Kind.LEGACY_ACTION, GoalType.FILL_BUCKET, Material.WATER_BUCKET, 1, !s.knowsWater),
                    Map.of("item:BUCKET", 1), Map.of("item:BUCKET", 1), Map.of("item:WATER_BUCKET", 1), s.knowsWater ? 8 : 45);
        }
        builder.leaf("mine:stone", GoalType.MINE_STONE, Map.of("cap:pickaxe:WOOD", 1), Map.of("item:COBBLESTONE", 8), 12, false);
        for (Material drop : List.of(Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.BLACKSTONE)) {
            builder.add("mine:stone:" + drop, new Operation(Kind.MINE_MATERIAL, GoalType.MINE_STONE, drop, 1, false),
                    Map.of("cap:pickaxe:WOOD", 1, "known:stone:" + drop.name(), 1), Map.of(), effects(drop, 1), 3);
        }
        builder.leaf("survey:iron", GoalType.FIND_IRON, Map.of("cap:pickaxe:STONE", 1), Map.of("known:iron", 1), 65, true);
        builder.leaf("mine:iron", GoalType.MINE_IRON, Map.of("cap:pickaxe:STONE", 1, "known:iron", 1), Map.of("item:RAW_IRON", 8), 18, false);
        builder.leaf("survey:diamond", GoalType.FIND_DIAMOND, Map.of("cap:pickaxe:IRON", 1), Map.of("known:diamond", 1), 110, true);
        builder.leaf("mine:diamond", GoalType.MINE_DIAMOND, Map.of("cap:pickaxe:IRON", 1, "known:diamond", 1), Map.of("item:DIAMOND", 3), 22, false);
        builder.add("survey:coal", new Operation(Kind.SURVEY_COAL, GoalType.MINE_COAL, null, 1, true),
                Map.of("cap:pickaxe:WOOD", 1), Map.of(), Map.of("known:coal", 1), 35);
        builder.leaf("mine:coal", GoalType.MINE_COAL, Map.of("cap:pickaxe:WOOD", 1, "known:coal", 1), Map.of("item:COAL", 8), 14, false);
        builder.add("place:table", new Operation(Kind.LEGACY_ACTION, GoalType.CRAFT_WORKBENCH, Material.CRAFTING_TABLE, 1, false),
                Map.of("item:CRAFTING_TABLE", 1), Map.of("item:CRAFTING_TABLE", 1), Map.of("world:table", 1), 3);
        builder.add("place:furnace", new Operation(Kind.PLACE_FURNACE, GoalType.SMELT_IRON, Material.FURNACE, 1, false),
                Map.of("item:FURNACE", 1), Map.of("item:FURNACE", 1), Map.of("world:furnace", 1, "world:idle-furnace", 1), 3);
        if (surfaceAllowed) {
            BuildPlans.Requirements needs = BuildPlans.requirements(ai);
            // All forecast building materials are consumed, including blocks already owned.
            for (String material : List.of("item:COBBLESTONE", "stock:planks", "mixed")) {
                Map<String, Integer> cost = new HashMap<>();
                if (needs.planks() > 0) cost.put("stock:planks", needs.planks());
                int remaining = needs.structuralBlocks();
                if (material.equals("mixed")) {
                    for (var entry : ai.getInventory().snapshot().entrySet()) {
                        if (!InventorySystem.isFiller(entry.getKey())) continue;
                        String token = item(entry.getKey());
                        int available = Math.max(0, entry.getValue() - cost.getOrDefault(token, 0));
                        int used = Math.min(available, remaining);
                        if (used > 0) cost.merge(token, used, Integer::sum);
                        remaining -= used;
                    }
                }
                if (remaining > 0) cost.merge(material.equals("mixed") ? "item:COBBLESTONE" : material, remaining, Integer::sum);
                builder.add("build:shelter:" + material, new Operation(Kind.LEGACY_ACTION, GoalType.BUILD_SHELTER, null, 1, true),
                        cost, cost, Map.of("world:shelter", 1), 35);
            }
        }
        if (s.obsidianWork) {
            builder.leaf("obsidian:continue", GoalType.GATHER_OBSIDIAN, Map.of("cap:pickaxe:DIAMOND", 1, "world:obsidian-work", 1),
                    Map.of("item:OBSIDIAN", 1), 12, true);
        }
        builder.leaf("obsidian:start", GoalType.GATHER_OBSIDIAN, Map.of("cap:pickaxe:DIAMOND", 1, "item:WATER_BUCKET", 1),
                Map.of("item:OBSIDIAN", 1), s.knowsLava ? 25 : 90, true);
        if (surfaceAllowed) {
            int needed = Math.max(0, 10 - state.count("stock:portal-frame"));
            Map<String, Integer> pre = new HashMap<>();
            pre.put("item:FLINT_AND_STEEL", 1);
            if (needed > 0) pre.put("item:OBSIDIAN", needed);
            builder.add("build:portal", new Operation(Kind.LEGACY_ACTION, GoalType.BUILD_PORTAL, null, 1, true),
                    pre, needed == 0 ? Map.of() : Map.of("item:OBSIDIAN", needed), Map.of("world:portal", 1), 35);
        }

        for (RecipeOption option : catalog.options) {
            Material target = Material.matchMaterial(option.result());
            if (target == null) continue;
            if (!option.smelting()) {
                for (Map<String, Integer> ingredients : variants(option, state)) {
                    Map<String, Integer> consumed = new HashMap<>(ingredients);
                    ingredients.forEach((token, count) -> {
                        Material input = token.startsWith("item:") ? Material.matchMaterial(token.substring(5)) : null;
                        if (input == null || !InventorySystem.isSafeFood(input)) return;
                        consumed.merge("stock:food", count, Integer::sum);
                        consumed.merge(InventorySystem.isRawFood(input) ? "stock:raw-food" : "stock:ready-food", count, Integer::sum);
                    });
                    Map<String, Integer> requirements = new HashMap<>(consumed);
                    if (option.needsTable()) requirements.put("world:table", 1);
                    Map<String, Integer> effects = effects(target, option.resultAmount());
                    builder.add("recipe:" + option.id() + ":" + new java.util.TreeMap<>(ingredients), new Operation(Kind.CRAFT, GoalType.CRAFT_TOOL,
                            target, option.resultAmount(), false, "", option.id()), requirements, consumed, effects, 2 + ingredients.values().stream().mapToInt(Integer::intValue).sum() * 0.1);
                }
            } else {
                Set<String> inputs = new java.util.HashSet<>();
                for (var ingredient : option.ingredients().getFirst().alternatives()) {
                    Material input = Material.matchMaterial(ingredient);
                    if (input == null || !inputs.add(item(input))) continue;
                    for (int batch : List.of(1, target == Material.IRON_INGOT ? 8 : 4)) {
                    for (String fuel : List.of("item:COAL", "item:CHARCOAL", "stock:planks")) {
                        Map<String, Integer> consumed = new HashMap<>();
                        consumed.put(item(input), batch);
                        if (InventorySystem.isSafeFood(input)) {
                            consumed.put("stock:food", batch);
                            consumed.put(InventorySystem.isRawFood(input) ? "stock:raw-food" : "stock:ready-food", batch);
                        }
                        consumed.merge(fuel, fuel.equals("stock:planks") ? (int) Math.ceil(batch / 1.5) : 1, Integer::sum);
                        consumed.put("world:idle-furnace", 1);
                        Map<String, Integer> pre = new HashMap<>(consumed);
                        pre.put("world:furnace", 1);
                        String pending = pending(target.name(), batch * option.resultAmount());
                        builder.add("smelt:" + option.id() + ":" + input + ":" + fuel + ":" + batch,
                                new Operation(Kind.SMELT, GoalType.SMELT_IRON, input, batch, false, fuel), pre, consumed,
                                Map.of(pending, 1), 8 + batch);
                        builder.add("collect:" + target + ":" + batch, new Operation(Kind.COLLECT_FURNACE, GoalType.TEND_FURNACE, target, batch, false),
                                Map.of(pending, 1), Map.of(pending, 1), withIdleFurnace(effects(target, batch * option.resultAmount())), 3);
                    }
                    }
                }
            }
        }
        // Cooking can use whichever raw food was actually acquired; species is chosen from the current inventory at execution.
        for (int batch : List.of(1, 4)) for (String fuel : List.of("item:COAL", "item:CHARCOAL", "stock:planks")) {
            Map<String, Integer> consumed = new HashMap<>();
            consumed.put("stock:raw-food", batch);
            consumed.put("stock:food", batch);
            consumed.put(fuel, fuel.equals("stock:planks") ? (int) Math.ceil(batch / 1.5) : 1);
            consumed.put("world:idle-furnace", 1);
            Map<String, Integer> pre = new HashMap<>(consumed);
            pre.put("world:furnace", 1);
            String pending = pending("ready-food", batch);
            builder.add("cook:food:" + batch + ":" + fuel, new Operation(Kind.SMELT, GoalType.COOK_FOOD, null, batch, false, fuel),
                    pre, consumed, Map.of(pending, 1), 8 + batch);
            builder.add("collect:ready-food:" + batch, new Operation(Kind.COLLECT_FURNACE, GoalType.TEND_FURNACE, null, batch, false),
                    Map.of(pending, 1), Map.of(pending, 1),
                    Map.of("stock:ready-food", batch, "stock:food", batch, "world:idle-furnace", 1), 3);
        }
        var currentJob = ai.getFurnaceJob();
        if (currentJob != null && currentJob.world().equals(ai.getWorldId()) && s.furnaceBusy) {
            Material result = Material.matchMaterial(currentJob.result());
            if (result != null && currentJob.count() > 0) {
                String pending = pending(currentJob.food() ? "ready-food" : result.name(), currentJob.count());
                Map<String, Integer> effect = currentJob.food()
                        ? Map.of("stock:ready-food", currentJob.count(), "stock:food", currentJob.count(), "world:idle-furnace", 1)
                        : withIdleFurnace(effects(result, currentJob.count()));
                builder.add("collect:current-job", new Operation(Kind.COLLECT_FURNACE, GoalType.TEND_FURNACE, result, currentJob.count(), false),
                        Map.of(pending, 1), Map.of(pending, 1), effect, 3);
            }
        }
        List<String> limits = new ArrayList<>();
        if (catalog.limited()) limits.add("제작 의존 재료 카탈로그 한도 " + MAX_RECIPE_MATERIALS);
        if (state.count("knowledge:farm-incomplete") > 0) limits.add("지역 농지 관측 진행 중 또는 범위 제한");
        if (state.count("knowledge:irrigation-incomplete") > 0) limits.add("지역 관개 후보 관측 진행 중 또는 범위 제한");
        if (state.count("knowledge:irrigation-unknown") > 0) limits.add("관개 주변 미관측 영역 있음");
        if (actions.size() >= 1024) limits.add("행동 후보 한도 1024");
        limits.add("레시피별 대체 재료 최대3·조합 최대" + MAX_RECIPE_VARIANTS);
        return new Snapshot(ai.getWorldId(), state, List.copyOf(actions.values()), operations, limits);
    }

    private static List<Map<String, Integer>> variants(RecipeOption option, PlanningState state) {
        List<Map<String, Integer>> variants = List.of(Map.of());
        for (var group : option.ingredients()) {
            List<String> choices = group.alternatives().stream().map(Material::matchMaterial).filter(java.util.Objects::nonNull)
                    .map(OverworldDomain::item).distinct().sorted(java.util.Comparator.<String>comparingInt(state::count).reversed().thenComparing(s -> s)).limit(3).toList();
            List<Map<String, Integer>> next = new ArrayList<>();
            outer: for (var previous : variants) for (String choice : choices) {
                var trial = new HashMap<>(previous);
                trial.merge(choice, group.amount(), Integer::sum);
                next.add(Map.copyOf(trial));
                if (next.size() >= MAX_RECIPE_VARIANTS) break outer;
            }
            variants = next;
        }
        return variants;
    }

    private static Map<String, Integer> effects(Material target, int amount) {
        Map<String, Integer> effects = new HashMap<>();
        effects.put(item(target), amount);
        if (InventorySystem.isSafeFood(target)) {
            effects.put("stock:food", amount);
            effects.put(InventorySystem.isRawFood(target) ? "stock:raw-food" : "stock:ready-food", amount);
        }
        if (Tag.ITEMS_PICKAXES.isTagged(target)) {
            ToolTier tier = ToolTier.of(target);
            for (ToolTier candidate : List.of(ToolTier.WOOD, ToolTier.STONE, ToolTier.IRON, ToolTier.DIAMOND)) {
                if (tier.isAtLeast(candidate)) effects.put("cap:pickaxe:" + candidate.name(), 1);
            }
        }
        if (Tag.ITEMS_SWORDS.isTagged(target) && ToolTier.of(target).isAtLeast(ToolTier.IRON)) effects.put("cap:sword:IRON", 1);
        if (Tag.ITEMS_HOES.isTagged(target)) effects.put("cap:hoe", 1);
        String name = target.name();
        if (name.startsWith("IRON_") || name.startsWith("DIAMOND_") || name.startsWith("NETHERITE_")) {
            if (name.endsWith("_HELMET")) effects.put("cap:armor:IRON:HEAD", 1);
            if (name.endsWith("_CHESTPLATE")) effects.put("cap:armor:IRON:CHEST", 1);
            if (name.endsWith("_LEGGINGS")) effects.put("cap:armor:IRON:LEGS", 1);
            if (name.endsWith("_BOOTS")) effects.put("cap:armor:IRON:FEET", 1);
        }
        return Map.copyOf(effects);
    }

    private static Map<String, Integer> withIdleFurnace(Map<String, Integer> effects) {
        var result = new HashMap<>(effects);
        result.put("world:idle-furnace", 1);
        return Map.copyOf(result);
    }

    private static String pending(String result, int amount) { return "pending:" + result + ":" + amount; }

    private record Builder(PlanningState state, Map<String, ActionModel> actions, Map<String, Operation> operations) {
        void leaf(String id, GoalType label, Map<String, Integer> pre, Map<String, Integer> effect, double cost, boolean exploratory) {
            add(id, new Operation(Kind.LEGACY_ACTION, label, null, 1, exploratory), pre, Map.of(), effect, cost);
        }
        void add(String id, Operation operation, Map<String, Integer> pre, Map<String, Integer> consumed, Map<String, Integer> effects, double cost) {
            if (actions.size() >= 1024) return;
            actions.putIfAbsent(id, new ActionModel(id, pre, consumed, effects, cost));
            operations.putIfAbsent(id, operation);
        }
    }
}
