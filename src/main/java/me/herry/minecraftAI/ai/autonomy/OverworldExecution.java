package me.herry.minecraftAI.ai.autonomy;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.PlaceBlockAction;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.plan.CraftPlans;
import me.herry.minecraftAI.ai.plan.HomePlans;
import me.herry.minecraftAI.ai.plan.Planner;
import me.herry.minecraftAI.ai.plan.ResourceOptions;
import me.herry.minecraftAI.ai.plan.ForagePlans;
import me.herry.minecraftAI.ai.plan.FarmingPlans;
import me.herry.minecraftAI.ai.plan.SeedGatheringPlans;
import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.List;

/** Instantiates just the first selected leaf. Future simulated steps are discarded and re-planned from reality. */
public final class OverworldExecution {
    public record Execution(List<Action> actions, boolean preparation) {
        public Execution { actions = List.copyOf(actions); }
    }

    private OverworldExecution() {}

    public static Execution create(OverworldAutonomy.Decision decision, AIPlayer ai, Planner planner) {
        var operation = decision.operation();
        List<Action> preparation = planner.prepareFooting(operation.label(), ai);
        if (!preparation.isEmpty()) return new Execution(preparation, true);
        List<Action> actions = switch (operation.kind()) {
            case LEGACY_ACTION, COLLECT_FURNACE -> planner.plan(operation.label(), ai);
            case PLACE_FURNACE -> List.of(new PlaceBlockAction(Material.FURNACE, MemoryType.FURNACE));
            case FETCH -> HomePlans.fetchItem(ai, operation.target(), operation.amount());
            case CRAFT -> craft(ai, operation.target(), operation.recipe());
            case MINE_MATERIAL -> ResourceOptions.mineStone(ai, operation.target());
            case SURVEY_COAL -> ResourceOptions.findCoal(ai);
            case FORAGE_BLOCK -> ForagePlans.gather(ai, operation.position(), operation.target());
            case PLANT_CROP -> FarmingPlans.plantAt(ai, operation.position(), operation.target());
            case INSPECT_CROP -> FarmingPlans.revisit(ai, operation.position());
            case GATHER_SEEDS -> SeedGatheringPlans.gather(ai, operation.position());
            case SMELT -> {
                Material input = operation.target();
                if (input == null) input = ai.getInventory().mostRawFood();
                if (input == null) yield List.of();
                if (Tag.LOGS.isTagged(input) && !ai.getInventory().has(input)) {
                    input = ai.getInventory().snapshot().keySet().stream().filter(Tag.LOGS::isTagged)
                            .sorted(java.util.Comparator.comparing(Material::name)).findFirst().orElse(input);
                }
                Material fuel = operation.fuel().equals("stock:planks")
                        ? ai.getInventory().snapshot().keySet().stream().filter(Tag.PLANKS::isTagged)
                        .sorted(java.util.Comparator.comparing(Material::name)).findFirst().orElse(null)
                        : Material.matchMaterial(operation.fuel().replace("item:", ""));
                if (fuel == null) yield List.of();
                yield CraftPlans.smeltItem(ai, input, operation.amount(), fuel);
            }
        };
        return new Execution(actions, false);
    }

    private static List<Action> craft(AIPlayer ai, Material target, String recipe) {
        if (Tag.PLANKS.isTagged(target)) {
            for (Material variant : Tag.PLANKS.getValues().stream().sorted(java.util.Comparator.comparing(Material::name)).toList()) {
                for (var option : ai.getCrafting().recipeOptions(variant)) {
                    List<Action> available = CraftPlans.craftRecipe(ai, variant, option.id());
                    if (!available.isEmpty()) return available;
                }
            }
            return List.of();
        }
        return CraftPlans.craftRecipe(ai, target, recipe);
    }
}
