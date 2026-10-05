package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.LegacyGoalAdapter;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.BuildGoal;
import me.herry.minecraftAI.ai.goal.model.CraftGoal;
import me.herry.minecraftAI.ai.goal.model.DefeatGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.goal.model.InteractGoal;
import me.herry.minecraftAI.ai.goal.model.ReachGoal;
import me.herry.minecraftAI.ai.goal.model.SurviveGoal;
import me.herry.minecraftAI.ai.observation.ItemGroups;
import me.herry.minecraftAI.ai.plan.Planner;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 목표를 맡을 스킬을 고르는 규칙. 계획 함수는 월드를 읽으므로 여기서는 가짜 함수를 등록해서 "어느 것이 불렸는지"만 본다.
 */
class SkillAndPlannerTest {
    // 음식을 얻는 방법 하나. 시험마다 비용과 쓸 수 있는지를 정한다.
    private record FoodSkill(String name, double cost, boolean usable, List<Action> plan) implements Skill {
        @Override
        public boolean supports(Goal goal) {
            return goal instanceof AcquireGoal acquire && acquire.item().equals(ItemGroups.FOOD);
        }

        @Override
        public boolean canExecute(Goal goal, @Nullable Situation situation) {
            return usable;
        }

        @Override
        public double estimateCost(Goal goal, @Nullable Situation situation) {
            return cost;
        }

        @Override
        public List<Action> buildPlan(AIPlayer ai, Goal goal, @Nullable Situation situation) {
            return plan;
        }
    }

    private static final Goal FOOD = new AcquireGoal(ItemGroups.FOOD, 5);

    // 등록된 계획 함수를 모두 "자기 이름을 적는" 가짜로 바꾼 Planner. 어느 목표의 계획 함수가 불렸는지 called 에 남는다.
    private static Planner recordingPlanner(List<GoalType> called) {
        Planner planner = new Planner();
        for (GoalType type : GoalType.values()) {
            planner.register(type, ai -> {
                called.add(type);
                return List.of(new WaitAction(1));
            });
        }
        return planner;
    }

    @Test
    void sameGoalCanHaveSeveralSkillsAndTheCheapestUsableOneWins() {
        SkillRegistry registry = new SkillRegistry();
        Action hunted = new WaitAction(10);
        Action looted = new WaitAction(20);
        registry.register(new FoodSkill("Hunt", 80.0, true, List.of(hunted)));
        registry.register(new FoodSkill("LootChest", 20.0, true, List.of(looted)));
        registry.register(new FoodSkill("Fish", 5.0, false, List.of(new WaitAction(30))));

        assertEquals(3, registry.candidates(FOOD).size());
        List<Skill> usable = registry.executable(FOOD, new Situation());
        assertEquals(List.of("LootChest", "Hunt"), usable.stream().map(Skill::name).toList());

        SkillPlan plan = new SkillPlanner(registry).plan(FOOD, null, new Situation());
        assertEquals("LootChest", plan.skill());
        assertSame(looted, plan.actions().get(0));
    }

    // 가장 싼 스킬이 지금은 계획을 세우지 못하면 그다음 스킬에게 넘어간다.
    @Test
    void fallsThroughWhenACheaperSkillHasNoPlan() {
        SkillRegistry registry = new SkillRegistry();
        Action hunted = new WaitAction(10);
        registry.register(new FoodSkill("LootChest", 20.0, true, List.of()));
        registry.register(new FoodSkill("Hunt", 80.0, true, List.of(hunted)));

        SkillPlan plan = new SkillPlanner(registry).plan(FOOD, null, new Situation());
        assertEquals("Hunt", plan.skill());
        assertSame(hunted, plan.actions().get(0));
    }

    @Test
    void noSkillMeansNoPlan() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new FoodSkill("Fish", 5.0, false, List.of(new WaitAction(30))));
        SkillPlan plan = new SkillPlanner(registry).plan(FOOD, null, new Situation());
        assertTrue(plan.isEmpty());
        assertEquals("", plan.skill());
        // 맡는 스킬이 아예 없는 목표도 마찬가지다.
        assertTrue(new SkillPlanner(registry).plan(new AcquireGoal("DIAMOND", 1), null, new Situation()).isEmpty());
    }

    @Test
    void registeringTheSameNameReplaces() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new FoodSkill("Hunt", 80.0, true, List.of()));
        Skill replacement = new FoodSkill("Hunt", 10.0, true, List.of());
        registry.register(replacement);
        assertEquals(1, registry.all().size());
        assertSame(replacement, registry.get("Hunt"));
    }

    // GoalSystem 이 고른 목표는 스킬 계층을 지나가도 그 목표의 계획 함수 하나만 불린다 (전과 같은 결과).
    @Test
    void everyLegacyGoalIsPlannedByItsOwnFunctionOnly() {
        List<GoalType> called = new ArrayList<>();
        Planner planner = recordingPlanner(called);
        for (GoalType type : GoalType.values()) {
            called.clear();
            Goal goal = LegacyGoalAdapter.toGoal(type, new Situation());
            // 물에 떠 있는지는 플레이어를 봐야 알 수 있으므로, 그 확인을 하지 않는 SkillPlanner 를 직접 부른다.
            SkillPlan plan = new SkillPlanner(planner.getSkills()).plan(goal, null, new Situation());
            assertEquals(List.of(type), called, type.name());
            assertEquals(LegacyPlannerSkill.nameOf(type), plan.skill());
            assertEquals(1, planner.getSkills().executable(goal, new Situation()).size(), type.name());
        }
    }

    // 그 목표의 계획 함수가 계획을 세우지 못하면 "계획 없음"이다. 비슷한 다른 목표의 함수로 넘어가지 않는다.
    @Test
    void legacyGoalDoesNotFallBackToAnotherSkill() {
        List<GoalType> called = new ArrayList<>();
        Planner planner = recordingPlanner(called);
        planner.register(GoalType.COLLECT_WOOD, ai -> List.of());
        Situation situation = new Situation();
        SkillPlan plan = new SkillPlanner(planner.getSkills()).plan(LegacyGoalAdapter.toGoal(GoalType.COLLECT_WOOD), null, situation);
        assertTrue(plan.isEmpty());
        assertTrue(called.isEmpty());
    }

    @Test
    void plannerKeepsTheGoalTypeEntryPoint() {
        Planner planner = new Planner();
        Action custom = new WaitAction(7);
        planner.register(GoalType.EXPLORE, ai -> List.of(custom));
        List<Action> actions = planner.plan(GoalType.EXPLORE, null);
        assertEquals(1, actions.size());
        assertSame(custom, actions.get(0));
    }

    @Test
    void everyGoalTypeHasASkill() {
        Planner planner = new Planner();
        for (GoalType type : GoalType.values()) {
            assertNotNull(planner.getSkills().get(LegacyPlannerSkill.nameOf(type)), type.name());
        }
    }

    // --- 새로 만든 범용 목표: 상황에 맞는 기존 계획으로 수행한다 ---

    private static GoalType routed(Goal goal, Situation situation) {
        List<GoalType> called = new ArrayList<>();
        Planner planner = recordingPlanner(called);
        // 범용 목표는 기존 목표의 계획으로 넘어갈 때 Planner.plan(GoalType) 을 거친다. 여기서 쓰는 목표는 물 확인을 하지 않는 것들이다.
        SkillPlan plan = new SkillPlanner(planner.getSkills()).plan(goal, null, situation);
        if (plan.isEmpty()) return null;
        assertEquals(1, called.size());
        return called.get(0);
    }

    @Test
    void woodGoesToKnownTreesFirst() {
        Goal wood = new AcquireGoal(ItemGroups.WOOD, 16);
        Situation situation = new Situation();
        assertEquals(GoalType.FIND_WOOD, routed(wood, situation));
        situation.knowsTree = true;
        assertEquals(GoalType.COLLECT_WOOD, routed(wood, situation));
    }

    @Test
    void foodGoalsUseHunting() {
        Situation situation = new Situation();
        assertEquals(GoalType.STOCK_FOOD, routed(FOOD, situation));
        assertEquals(GoalType.FIND_FOOD, routed(new SurviveGoal(SurviveGoal.Reason.EAT), situation));
    }

    // 광물은 그것을 캘 수 있는 곡괭이가 있어야 맡는다. 물에 떠 있는지 확인이 필요한 목표들이라 경로만 본다.
    @Test
    void miningNeedsTheRightPickaxe() {
        MineResourceSkill skill = new MineResourceSkill(new Planner());
        Goal iron = new AcquireGoal("RAW_IRON", 3);
        Goal diamond = new AcquireGoal("DIAMOND", 3);
        Situation situation = new Situation();
        assertTrue(skill.supports(iron));
        assertFalse(skill.canExecute(iron, situation));
        assertNull(skill.route(iron, situation));

        situation.hasPickaxe = true;
        situation.canMineIron = true;
        assertEquals(GoalType.FIND_IRON, skill.route(iron, situation));
        situation.knowsIron = true;
        assertEquals(GoalType.MINE_IRON, skill.route(iron, situation));
        assertTrue(skill.estimateCost(iron, situation) < RoutedSkill.SEARCH_COST);
        assertFalse(skill.canExecute(diamond, situation));

        situation.canMineDiamond = true;
        assertEquals(GoalType.FIND_DIAMOND, skill.route(diamond, situation));
        assertEquals(RoutedSkill.SEARCH_COST, skill.estimateCost(diamond, situation));
        situation.knowsDiamond = true;
        assertEquals(GoalType.MINE_DIAMOND, skill.route(diamond, situation));

        assertEquals(GoalType.MINE_STONE, skill.route(new AcquireGoal(ItemGroups.STONE, 16), situation));
        assertEquals(GoalType.FIND_IRON, skill.route(new AcquireGoal(ItemGroups.COAL, 4), situation));
        situation.knowsCoal = true;
        assertEquals(GoalType.MINE_COAL, skill.route(new AcquireGoal(ItemGroups.COAL, 4), situation));
        // 상황을 모르면 쓸 수 없다.
        assertFalse(skill.canExecute(iron, null));
    }

    @Test
    void directRoutesCheckTheirPreconditions() {
        DirectRouteSkill skill = new DirectRouteSkill(new Planner());
        Situation situation = new Situation();
        assertNull(skill.route(ReachGoal.home(8.0), situation));
        assertNull(skill.route(new InteractGoal(InteractGoal.Interaction.SLEEP), situation));
        assertNull(skill.route(new DefeatGoal(DefeatGoal.HOSTILE, 1), situation));
        assertNull(skill.route(new AcquireGoal("IRON_INGOT", 3), situation));
        assertNull(skill.route(new BuildGoal(BuildGoal.Structure.SHELTER), situation));

        situation.homeKnown = true;
        situation.canSleep = true;
        situation.combat = CombatSystem.Decision.FIGHT;
        situation.rawIron = 2;
        situation.canBuildHere = true;
        assertEquals(GoalType.RETURN_HOME, skill.route(ReachGoal.home(8.0), situation));
        assertEquals(GoalType.SLEEP, skill.route(new InteractGoal(InteractGoal.Interaction.SLEEP), situation));
        assertEquals(GoalType.FIGHT_HOSTILE, skill.route(new DefeatGoal(DefeatGoal.HOSTILE, 1), situation));
        assertEquals(GoalType.SMELT_IRON, skill.route(new AcquireGoal("IRON_INGOT", 3), situation));
        assertEquals(GoalType.BUILD_SHELTER, skill.route(new BuildGoal(BuildGoal.Structure.SHELTER), situation));
        assertEquals(GoalType.CRAFT_WORKBENCH, skill.route(new CraftGoal(CraftGoal.CRAFTING_TABLE, 1), situation));
    }

    // 아직 수행할 방법이 없는 목표는 맡는 스킬이 없다. 되는 척하지 않는다.
    @Test
    void unsupportedGoalsHaveNoSkill() {
        SkillRegistry skills = new Planner().getSkills();
        assertTrue(skills.candidates(ReachGoal.dimension(ReachGoal.Place.NETHER)).isEmpty());
        assertTrue(skills.candidates(new BuildGoal(BuildGoal.Structure.NETHER_PORTAL)).isEmpty());
        assertTrue(skills.candidates(new DefeatGoal("BLAZE", 6)).isEmpty());
        assertTrue(skills.candidates(new AcquireGoal("OBSIDIAN", 10)).isEmpty());
    }

    // 기존 목표를 옮겨 적은 것은 범용 스킬이 가로채지 않는다.
    @Test
    void genericSkillsLeaveLegacyGoalsAlone() {
        Planner planner = new Planner();
        Goal legacyWood = LegacyGoalAdapter.toGoal(GoalType.FIND_WOOD);
        assertEquals(List.of(LegacyPlannerSkill.nameOf(GoalType.FIND_WOOD)),
                planner.getSkills().candidates(legacyWood).stream().map(Skill::name).toList());
        // 제작도 마찬가지다. 범용 제작 스킬은 새로 만든 제작 목표만 맡는다.
        assertEquals(List.of("CraftItem"), planner.getSkills().candidates(new CraftGoal("IRON_PICKAXE", 1)).stream().map(Skill::name).toList());
    }
}
