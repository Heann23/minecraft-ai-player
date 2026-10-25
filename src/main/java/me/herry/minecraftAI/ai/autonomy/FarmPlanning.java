package me.herry.minecraftAI.ai.autonomy;

import me.herry.minecraftAI.ai.plan.FarmingPlans;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

/** Value-only planning facts: propagation is production capacity, never an immediately edible harvest. */
public final class FarmPlanning {
    private FarmPlanning() {}

    public static String site(Material seed, BlockPoint soil) { return "known:plantable:" + seed + ":" + soil.encode(); }
    public static String soil(BlockPoint soil) { return "known:plantable-soil:" + soil.encode(); }
    public static String planted(Material seed, BlockPoint soil) { return "planted:" + seed + ":" + soil.encode(); }
    public static String due(BlockPoint soil) { return "known:farm-due:" + soil.encode(); }
    public static String checked(BlockPoint soil) { return "known:farm-checked:" + soil.encode(); }

    public static Map<String, Integer> observed(FarmingPlans.Snapshot snapshot) {
        Map<String, Integer> facts = new HashMap<>();
        for (var crop : snapshot.crops()) {
            if (crop.stage() != FarmingPlans.Stage.PLANTED && crop.stage() != FarmingPlans.Stage.MATURE) continue;
            facts.merge("world:crop-planted", 1, Integer::sum);
            facts.put(planted(crop.seed(), crop.soil()), 1);
        }
        for (var candidate : snapshot.plantable()) {
            facts.put(site(candidate.seed(), candidate.soil()), 1);
            facts.put(soil(candidate.soil()), 1);
        }
        if (!snapshot.complete()) facts.put("knowledge:farm-incomplete", 1);
        return Map.copyOf(facts);
    }

    public static int requiredSeedCount(FarmingPlans.Snapshot snapshot, Material seed) {
        return snapshot.crops().stream().anyMatch(crop -> crop.stage() == FarmingPlans.Stage.UNKNOWN
                || crop.seed() == seed && (crop.stage() == FarmingPlans.Stage.PLANTED || crop.stage() == FarmingPlans.Stage.MATURE)) ? 2 : 1;
    }

    public static ActionModel planting(FarmingPlans.PlantingSite candidate, int seedThreshold) {
        Map<String, Integer> pre = new HashMap<>();
        Map<String, Integer> consumed = new HashMap<>();
        String location = site(candidate.seed(), candidate.soil());
        pre.put(location, 1); consumed.put(location, 1);
        pre.put(soil(candidate.soil()), 1); consumed.put(soil(candidate.soil()), 1);
        pre.put("item:" + candidate.seed().name(), seedThreshold);
        consumed.put("item:" + candidate.seed().name(), 1);
        if (candidate.seed() == Material.CARROT || candidate.seed() == Material.POTATO) {
            pre.put("stock:food", 1); consumed.put("stock:food", 1);
            String food = candidate.seed() == Material.POTATO ? "stock:raw-food" : "stock:ready-food";
            pre.put(food, 1); consumed.put(food, 1);
        }
        if (candidate.needsTilling()) pre.put("cap:hoe", 1);
        return new ActionModel("plant:" + candidate.seed() + ":" + candidate.soil().encode(), pre, consumed,
                Map.of("world:crop-planted", 1, planted(candidate.seed(), candidate.soil()), 1), candidate.needsTilling() ? 10 : 6);
    }
}
