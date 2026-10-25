package me.herry.minecraftAI.ai.autonomy;

import me.herry.minecraftAI.ai.plan.IrrigationPlans;
import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.Map;

/** Installing a source creates water access, not hydrated farmland, crops or food. */
public final class IrrigationPlanning {
    private IrrigationPlanning() {}

    public static String site(BlockPoint cell) { return "known:irrigation:" + cell.encode(); }
    public static String water(BlockPoint cell) { return "world:irrigation:" + cell.encode(); }

    public static ActionModel install(IrrigationPlans.Site candidate) {
        String source = site(candidate.cell());
        return new ActionModel("irrigate:" + candidate.cell().encode(),
                Map.of(source, 1, "item:WATER_BUCKET", 1),
                Map.of(source, 1, "item:WATER_BUCKET", 1),
                Map.of(water(candidate.cell()), 1, "item:BUCKET", 1),
                (candidate.needsExcavation() ? 14 : 9) / Math.max(1.0, candidate.benefit() / 2.0));
    }
}
