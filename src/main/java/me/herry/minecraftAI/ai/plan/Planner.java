package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.DropJunkAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.LegacyGoalAdapter;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.skill.ChopWoodSkill;
import me.herry.minecraftAI.ai.skill.CraftItemSkill;
import me.herry.minecraftAI.ai.skill.DirectRouteSkill;
import me.herry.minecraftAI.ai.skill.EscapeDangerSkill;
import me.herry.minecraftAI.ai.skill.HuntPreySkill;
import me.herry.minecraftAI.ai.skill.LegacyPlannerSkill;
import me.herry.minecraftAI.ai.skill.MineResourceSkill;
import me.herry.minecraftAI.ai.skill.SkillPlan;
import me.herry.minecraftAI.ai.skill.SkillPlanner;
import me.herry.minecraftAI.ai.skill.SkillRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 목표를 달성하기 위한 행동 순서를 만든다.
 * 목표마다 계획을 만드는 함수가 따로 등록되어 있어서, 새 목표를 추가할 때 여기에 한 줄만 등록하면 된다.
 *
 * 등록한 함수는 스킬(LegacyPlannerSkill)로도 들어가고, 계획은 언제나 목표 -> SkillPlanner -> 스킬의 순서로 세운다.
 * GoalSystem 이 고른 목표는 그 목표의 함수로만 세우므로 결과는 전과 같다. 새로 만든 범용 목표는 맞는 스킬 중에서 골라 세운다.
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

    // 물에서 먼저 나오는 계획은 어느 스킬의 것도 아니라서 이 이름으로 적는다.
    public static final String LEAVE_WATER = "LeaveWater";

    private final SkillRegistry skills = new SkillRegistry();
    private final SkillPlanner skillPlanner = new SkillPlanner(skills);

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
        register(GoalType.GATHER_FLINT, GatherPlans::gatherFlint);
        register(GoalType.FILL_BUCKET, BucketPlans::fillBucket);
        register(GoalType.EXPLORE, GatherPlans::explore);
        register(GoalType.CRAFT_WORKBENCH, CraftPlans::craftWorkbench);
        register(GoalType.CRAFT_TOOL, CraftPlans::craftTool);
        register(GoalType.CRAFT_WORK_TOOL, CraftPlans::craftWorkTool);

        // 새로 만든 범용 목표를 수행하는 스킬. 기존 목표를 옮겨 적은 것은 위에서 등록한 함수가 그대로 맡는다.
        skills.register(new EscapeDangerSkill(this));
        skills.register(new MineResourceSkill(this));
        skills.register(new ChopWoodSkill(this));
        skills.register(new HuntPreySkill(this));
        skills.register(new CraftItemSkill());
        skills.register(new DirectRouteSkill(this));
    }

    public SkillRegistry getSkills() {
        return skills;
    }

    public void register(GoalType goal, GoalPlanner planner) {
        skills.register(new LegacyPlannerSkill(goal, planner));
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

    // 기존 목표의 계획. 그 목표를 옮겨 적은 범용 목표로 세우는 것과 같다.
    public List<Action> plan(GoalType goal, AIPlayer ai) {
        return plan(LegacyGoalAdapter.toGoal(goal), ai, null).actions();
    }

    /**
     * 목표를 이루는 계획과, 그 계획을 세운 스킬.
     *
     * @param situation 판단할 때의 상황. 새로 만든 범용 목표는 이것을 보고 스킬을 고른다 (null 이면 계획이 서지 않는다).
     *                  기존 목표를 옮겨 적은 것은 상황 없이도 그 목표의 함수로 세운다.
     */
    public SkillPlan plan(Goal goal, AIPlayer ai, @Nullable Situation situation) {
        GoalType legacy = LegacyGoalAdapter.toLegacy(goal);
        // 물에 떠 있는 채로는 캐는 속도가 훨씬 느리고, 블록을 놓거나 굴을 팔 수도 없다. 가까이에 마른 땅이 있으면 먼저 나온다.
        if (legacy != null && NEEDS_FOOTING.contains(legacy) && ai.getPlayer().isInWater() && !ai.getBody().isGrounded()) {
            List<Action> leave = TerrainPlans.leaveWater(ai);
            if (!leave.isEmpty()) return new SkillPlan(LEAVE_WATER, leave);
        }
        return skillPlanner.plan(goal, ai, situation);
    }
}
