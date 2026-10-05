package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.BuildGoal;
import me.herry.minecraftAI.ai.goal.model.CraftGoal;
import me.herry.minecraftAI.ai.goal.model.DefeatGoal;
import me.herry.minecraftAI.ai.goal.model.ExploreGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.goal.model.GoalMetadata;
import me.herry.minecraftAI.ai.goal.model.InteractGoal;
import me.herry.minecraftAI.ai.goal.model.LegacyGoalWrapper;
import me.herry.minecraftAI.ai.goal.model.ReachGoal;
import me.herry.minecraftAI.ai.goal.model.SurviveGoal;
import me.herry.minecraftAI.ai.observation.ItemGroups;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 기존 GoalType 과 범용 Goal 사이를 잇는다. GoalSystem 은 그대로 GoalType 을 고르고, 여기서 그것을 "무엇을 얼마나"로 옮겨 적는다.
 *
 * 옮겨 적은 목표는 어느 GoalType 에서 왔는지를 metadata 에 들고 있어서 toLegacy 로 언제나 되돌릴 수 있다.
 * 그래서 계획은 전과 똑같이 그 GoalType 의 계획 함수가 세운다.
 * 값으로 적을 수 없는 목표(가방 정리 등)는 LegacyGoalWrapper 로 감싼다.
 */
public final class LegacyGoalAdapter {
    private LegacyGoalAdapter() {
    }

    public static Goal toGoal(GoalType type) {
        return toGoal(type, null);
    }

    /**
     * @param situation 목표를 고를 때의 상황. 얼마나 필요한지(철 몇 개, 무엇을 만들지)를 여기서 읽는다.
     *                  null 이면 개수는 1 로, 만들 것은 정해지지 않은 것으로 적는다.
     */
    public static Goal toGoal(GoalType type, @Nullable Situation situation) {
        Objects.requireNonNull(type, "type");
        GoalMetadata origin = GoalMetadata.from(type);
        return switch (type) {
            case ESCAPE_DANGER -> new SurviveGoal(SurviveGoal.Reason.ESCAPE_DANGER, origin);
            case SURVIVE -> new SurviveGoal(SurviveGoal.Reason.RECOVER_HEALTH, origin);
            case FIND_FOOD -> new SurviveGoal(SurviveGoal.Reason.EAT, origin);
            case FIGHT_HOSTILE -> new DefeatGoal(DefeatGoal.HOSTILE, 1, origin);
            case ASSIST_ALLY -> new DefeatGoal(DefeatGoal.ALLY_THREAT, 1, origin);
            case RETURN_HOME -> new ReachGoal(ReachGoal.Place.HOME, null, GoalSystem.HOME_NEAR, origin);
            case SLEEP -> new InteractGoal(InteractGoal.Interaction.SLEEP, origin);
            case LOOT_CHEST -> new InteractGoal(InteractGoal.Interaction.LOOT_CHEST, origin);
            case FIND_WOOD, COLLECT_WOOD -> new AcquireGoal(ItemGroups.WOOD, GoalSystem.WOOD_TARGET, origin);
            case MINE_STONE -> new AcquireGoal(ItemGroups.STONE, GoalSystem.STONE_TARGET, origin);
            case MINE_COAL -> new AcquireGoal(ItemGroups.COAL, GoalSystem.COAL_MIN, origin);
            // 원석은 주괴로 구워야 쓰므로, 아직 모자란 주괴 수만큼 캔다.
            case FIND_IRON, MINE_IRON -> new AcquireGoal("RAW_IRON",
                    situation == null ? 1 : Math.max(1, situation.ironNeeded - situation.ironIngots), origin);
            case SMELT_IRON -> new AcquireGoal("IRON_INGOT", situation == null ? 1 : Math.max(1, situation.ironNeeded), origin);
            case FIND_DIAMOND, MINE_DIAMOND -> new AcquireGoal("DIAMOND",
                    situation == null ? 1 : Math.max(1, situation.diamondsNeeded), origin);
            case GATHER_FLINT -> new AcquireGoal("FLINT", 1, origin);
            case STOCK_FOOD -> new AcquireGoal(ItemGroups.FOOD, GoalSystem.FOOD_STOCK, origin);
            case CRAFT_WORKBENCH -> new CraftGoal(CraftGoal.CRAFTING_TABLE, 1, origin);
            case CRAFT_WORK_TOOL -> new CraftGoal("STONE_PICKAXE", 1, origin);
            case CRAFT_TORCH -> new CraftGoal("TORCH", GoalSystem.TORCH_MIN, origin);
            case CRAFT_TOOL -> {
                String item = situation == null ? null : craftedItem(situation.nextMilestone);
                yield item == null ? new LegacyGoalWrapper(type, origin) : new CraftGoal(item, 1, origin);
            }
            case BUILD_SHELTER -> new BuildGoal(BuildGoal.Structure.SHELTER, origin);
            case EXPLORE -> new ExploreGoal(origin);
            case IDLE, SHARE_FOOD, CLEAN_INVENTORY, STORE_ITEMS, FETCH_ITEMS, PICKUP_ITEMS, PACK_UP_TABLE, COOK_FOOD,
                 TEND_FURNACE, PACK_UP_FURNACE -> new LegacyGoalWrapper(type, origin);
        };
    }

    /**
     * 이 목표가 온 GoalType. 기존 목표를 옮겨 적은 것이 아니면(새로 만든 범용 목표) null 이다.
     */
    public static @Nullable GoalType toLegacy(Goal goal) {
        return goal.metadata().legacyOrigin();
    }

    /**
     * 그 항목을 이루려고 만드는 아이템의 Material 이름. 제작으로 이루는 항목이 아니면 null.
     * Progression.materialOf 와 같은 답을 낸다 (GoalModelTest 가 둘을 맞춰 본다).
     */
    static @Nullable String craftedItem(@Nullable Milestone milestone) {
        if (milestone == null || milestone.kind() != Milestone.Kind.CRAFT) return null;
        return milestone == Milestone.EYES_OF_ENDER ? "ENDER_EYE" : milestone.name();
    }
}
