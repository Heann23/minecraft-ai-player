package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.DropJunkAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.goal.GoalType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 목표를 달성하기 위한 행동 순서를 만든다.
 * 목표마다 계획을 만드는 함수가 따로 등록되어 있어서, 새 목표를 추가할 때 여기에 한 줄만 등록하면 된다.
 */
public final class Planner {
    @FunctionalInterface
    public interface GoalPlanner {
        // 지금은 계획을 세울 수 없으면 빈 목록을 돌려준다.
        List<Action> plan(AIPlayer ai);
    }

    private static final int IDLE_TICKS = 40;
    private static final double IN_SHAFT_RANGE = 1.5;
    // 한자리에 서서 하는 일. 물에 떠 있으면 먼저 마른 땅으로 나와서 한다.
    private static final Set<GoalType> NEEDS_FOOTING = EnumSet.of(GoalType.MINE_STONE, GoalType.MINE_COAL, GoalType.MINE_IRON,
            GoalType.MINE_DIAMOND, GoalType.FIND_IRON, GoalType.FIND_DIAMOND, GoalType.CRAFT_WORKBENCH, GoalType.CRAFT_TOOL,
            GoalType.CRAFT_WORK_TOOL, GoalType.COOK_FOOD, GoalType.SMELT_IRON, GoalType.BUILD_SHELTER);

    private final Map<GoalType, GoalPlanner> planners = new EnumMap<>(GoalType.class);

    public Planner() {
        register(GoalType.IDLE, ai -> List.of(new WaitAction(IDLE_TICKS)));
        register(GoalType.ESCAPE_DANGER, SurvivalPlans::escapeDanger);
        register(GoalType.SURVIVE, SurvivalPlans::survive);
        register(GoalType.FIGHT_HOSTILE, SurvivalPlans::fightHostile);
        register(GoalType.ASSIST_ALLY, SurvivalPlans::assistAlly);
        register(GoalType.FIND_FOOD, SurvivalPlans::findFood);
        register(GoalType.RETURN_HOME, HomePlans::returnHome);
        register(GoalType.SLEEP, HomePlans::sleep);
        register(GoalType.STORE_ITEMS, HomePlans::storeItems);
        register(GoalType.FETCH_ITEMS, HomePlans::fetchItems);
        register(GoalType.BUILD_SHELTER, BuildPlans::buildShelter);
        register(GoalType.SHARE_FOOD, SurvivalPlans::shareFood);
        register(GoalType.STOCK_FOOD, SurvivalPlans::stockFood);
        register(GoalType.LOOT_CHEST, GatherPlans::lootChest);
        register(GoalType.MINE_COAL, GatherPlans::mineCoal);
        register(GoalType.SMELT_IRON, CraftPlans::smeltIron);
        register(GoalType.COOK_FOOD, CraftPlans::cookFood);
        register(GoalType.PACK_UP_TABLE, CraftPlans::packUpTable);
        register(GoalType.TEND_FURNACE, FurnacePlans::tendFurnace);
        register(GoalType.PACK_UP_FURNACE, FurnacePlans::packUpFurnace);
        register(GoalType.CRAFT_TORCH, CraftPlans::craftTorch);
        register(GoalType.PICKUP_ITEMS, GatherPlans::pickupItems);
        register(GoalType.CLEAN_INVENTORY, ai -> List.of(new DropJunkAction()));
        register(GoalType.FIND_WOOD, GatherPlans::findWood);
        register(GoalType.COLLECT_WOOD, GatherPlans::collectWood);
        register(GoalType.MINE_STONE, GatherPlans::mineStone);
        register(GoalType.FIND_IRON, GatherPlans::findIron);
        register(GoalType.MINE_IRON, GatherPlans::mineIron);
        register(GoalType.FIND_DIAMOND, GatherPlans::findDiamond);
        register(GoalType.MINE_DIAMOND, GatherPlans::mineDiamond);
        register(GoalType.EXPLORE, GatherPlans::explore);
        register(GoalType.CRAFT_WORKBENCH, CraftPlans::craftWorkbench);
        register(GoalType.CRAFT_TOOL, CraftPlans::craftTool);
        register(GoalType.CRAFT_WORK_TOOL, CraftPlans::craftWorkTool);
    }

    public void register(GoalType goal, GoalPlanner planner) {
        planners.put(goal, planner);
    }

    // 걸어서 나갈 수 없는 곳에서 빠져나오는 계획. 방법이 없거나 빠져나올 필요가 없으면 빈 목록.
    public List<Action> planEscape(GoalType goal, AIPlayer ai) {
        // 땅속에서 광물을 캐는 중이라면 좁은 굴 안에 있는 것이 정상이다. 지상으로 올라가지 않는다.
        boolean mining = goal == GoalType.FIND_IRON || goal == GoalType.MINE_IRON || goal == GoalType.MINE_STONE
                || goal == GoalType.MINE_COAL || goal == GoalType.FIND_DIAMOND || goal == GoalType.MINE_DIAMOND;
        boolean miningDeep = mining && TerrainPlans.isDeepUnderground(ai.getPlayer().getWorld(), ai.getPosition());
        // 파 놓은 굴 안은 좁아서 경로 탐색이 "갇혔다"고 판단하기 쉽지만, 굴을 따라 오갈 수 있으면 갇힌 것이 아니다.
        // 그 굴을 따라가려다 방금 실패했거나 굴의 위쪽이 가까이에서 막혀 있으면(계단 칸에 놓인 화로 등) 새로 길을 판다.
        // 굴이 멀쩡할 때 걸어 나가게 하지는 않는다. 닿지 못하는 광석 하나 때문에 밤의 지상까지 올라가게 된다.
        boolean inShaft = ai.getTeam().getShafts().passesNear(ai.getWorldId(), ai.getPosition(), IN_SHAFT_RANGE);
        boolean shaftBlocked = ShaftPlans.shaftFailedRecently(ai) || inShaft && !miningDeep && ShaftPlans.wayUpBlocked(ai);
        if (!DigRules.shouldDigOut(miningDeep, inShaft, shaftBlocked)) return List.of();
        return GatherPlans.digWayOut(ai);
    }

    public List<Action> plan(GoalType goal, AIPlayer ai) {
        // 물에 떠 있는 채로는 캐는 속도가 훨씬 느리고, 블록을 놓거나 굴을 팔 수도 없다. 가까이에 마른 땅이 있으면 먼저 나온다.
        if (NEEDS_FOOTING.contains(goal) && ai.getPlayer().isInWater() && !ai.getBody().isGrounded()) {
            List<Action> leave = TerrainPlans.leaveWater(ai);
            if (!leave.isEmpty()) return leave;
        }
        GoalPlanner planner = planners.get(goal);
        return planner == null ? List.of() : planner.plan(ai);
    }
}
