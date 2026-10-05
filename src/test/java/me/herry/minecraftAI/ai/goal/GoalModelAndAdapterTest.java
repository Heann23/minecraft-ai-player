package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import me.herry.minecraftAI.ai.goal.model.BuildGoal;
import me.herry.minecraftAI.ai.goal.model.CraftGoal;
import me.herry.minecraftAI.ai.goal.model.DefeatGoal;
import me.herry.minecraftAI.ai.goal.model.ExploreGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import me.herry.minecraftAI.ai.goal.model.GoalCategory;
import me.herry.minecraftAI.ai.goal.model.InteractGoal;
import me.herry.minecraftAI.ai.goal.model.LegacyGoalWrapper;
import me.herry.minecraftAI.ai.goal.model.ReachGoal;
import me.herry.minecraftAI.ai.goal.model.SurviveGoal;
import me.herry.minecraftAI.ai.observation.EnvironmentState;
import me.herry.minecraftAI.ai.observation.ItemGroups;
import me.herry.minecraftAI.ai.observation.ObservationFixture;
import me.herry.minecraftAI.ai.observation.PlayerState;
import me.herry.minecraftAI.ai.plan.Progression;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 범용 목표의 완료 조건과, 기존 GoalType 을 범용 목표로 옮겨 적는 규칙.
 */
class GoalModelAndAdapterTest {
    private static ObservationFixture state() {
        return new ObservationFixture();
    }

    // 어느 GoalType 이든 옮겨 적은 목표에서 원래의 GoalType 을 되찾을 수 있어야 한다. 그래야 계획이 전과 같은 함수로 세워진다.
    @Test
    void everyGoalTypeRoundTrips() {
        for (GoalType type : GoalType.values()) {
            Goal goal = LegacyGoalAdapter.toGoal(type);
            assertEquals(type, LegacyGoalAdapter.toLegacy(goal), type.name());
            assertFalse(goal.describe().isBlank(), type.name());

            Goal withSituation = LegacyGoalAdapter.toGoal(type, new Situation());
            assertEquals(type, LegacyGoalAdapter.toLegacy(withSituation), type.name());
        }
    }

    // 새로 만든 목표는 기존 GoalType 에서 온 것이 아니다.
    @Test
    void freshGoalHasNoLegacyOrigin() {
        assertNull(LegacyGoalAdapter.toLegacy(new AcquireGoal("DIAMOND", 3)));
        // 같은 것을 가리켜도, 어디서 왔는지가 다르면 다른 목표다 (계획을 세우는 방법이 다르다).
        assertNotEquals(new AcquireGoal("DIAMOND", 1), LegacyGoalAdapter.toGoal(GoalType.MINE_DIAMOND));
    }

    @Test
    void amountsComeFromTheSituation() {
        Situation situation = new Situation();
        situation.ironNeeded = 8;
        situation.ironIngots = 3;
        situation.diamondsNeeded = 3;

        AcquireGoal raw = assertInstanceOf(AcquireGoal.class, LegacyGoalAdapter.toGoal(GoalType.MINE_IRON, situation));
        assertEquals("RAW_IRON", raw.item());
        assertEquals(5, raw.count());
        AcquireGoal ingots = assertInstanceOf(AcquireGoal.class, LegacyGoalAdapter.toGoal(GoalType.SMELT_IRON, situation));
        assertEquals("IRON_INGOT", ingots.item());
        assertEquals(8, ingots.count());
        AcquireGoal diamonds = assertInstanceOf(AcquireGoal.class, LegacyGoalAdapter.toGoal(GoalType.FIND_DIAMOND, situation));
        assertEquals(3, diamonds.count());

        // 이미 넉넉해도 개수는 1 아래로 내려가지 않는다.
        situation.ironIngots = 20;
        assertEquals(1, ((AcquireGoal) LegacyGoalAdapter.toGoal(GoalType.FIND_IRON, situation)).count());
    }

    // 장비 제작은 다음에 이룰 것이 무엇이냐에 따라 만드는 아이템이 달라진다.
    @Test
    void craftToolNamesTheNextMilestone() {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_PICKAXE;
        CraftGoal craft = assertInstanceOf(CraftGoal.class, LegacyGoalAdapter.toGoal(GoalType.CRAFT_TOOL, situation));
        assertEquals("IRON_PICKAXE", craft.item());

        // 만들 것이 정해지지 않았거나 제작으로 이루는 항목이 아니면 값으로 적지 못한다.
        assertInstanceOf(LegacyGoalWrapper.class, LegacyGoalAdapter.toGoal(GoalType.CRAFT_TOOL));
        situation.nextMilestone = Milestone.SHELTER;
        assertInstanceOf(LegacyGoalWrapper.class, LegacyGoalAdapter.toGoal(GoalType.CRAFT_TOOL, situation));
    }

    // 목표에 적는 아이템 이름은 실제로 만드는 아이템(Progression.materialOf)과 같아야 한다.
    @Test
    void craftedItemMatchesProgression() {
        for (Milestone milestone : Milestone.values()) {
            String item = LegacyGoalAdapter.craftedItem(milestone);
            if (milestone.kind() == Milestone.Kind.CRAFT) assertEquals(Progression.materialOf(milestone).name(), item, milestone.name());
            else assertNull(item, milestone.name());
        }
    }

    @Test
    void acquireCountsWhatIsHeldNow() {
        AcquireGoal goal = new AcquireGoal("RAW_IRON", 3);
        assertEquals(GoalCategory.ACQUIRE, goal.category());
        assertFalse(goal.isAchieved(state().build()));
        assertFalse(goal.isAchieved(state().item("RAW_IRON", 2).build()));
        assertTrue(goal.isAchieved(state().item("RAW_IRON", 3).build()));
        // 다른 아이템은 세지 않는다. 주괴가 있어도 원석을 가진 것은 아니다.
        assertFalse(goal.isAchieved(state().item("IRON_INGOT", 9).build()));
    }

    @Test
    void acquireGroupsUseTheirOwnTotals() {
        ObservationFixture fixture = state();
        fixture.plankEquivalent = 16;
        fixture.food = 7;
        assertTrue(new AcquireGoal(ItemGroups.WOOD, 16).isAchieved(fixture.build()));
        assertFalse(new AcquireGoal(ItemGroups.FOOD, 8).isAchieved(fixture.build()));
        assertFalse(new AcquireGoal(ItemGroups.STONE, 1).isAchieved(fixture.build()));
        // 모르는 묶음 이름은 가진 것이 없는 것으로 본다.
        assertFalse(new AcquireGoal("#UNKNOWN", 1).isAchieved(fixture.build()));
    }

    @Test
    void countsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new AcquireGoal("DIAMOND", 0));
        assertThrows(IllegalArgumentException.class, () -> new CraftGoal("TORCH", -1));
        assertThrows(IllegalArgumentException.class, () -> new DefeatGoal("BLAZE", 0));
    }

    @Test
    void craftedStationCountsWhenPlacedNearby() {
        CraftGoal table = new CraftGoal(CraftGoal.CRAFTING_TABLE, 1);
        assertFalse(table.isAchieved(state().build()));
        assertTrue(table.isAchieved(state().item("CRAFTING_TABLE", 1).build()));
        ObservationFixture placed = state();
        placed.tableAvailable = true;
        assertTrue(table.isAchieved(placed.build()));

        // 도구는 가지고 있어야 한다. 근처에 작업대가 있다고 곡괭이가 있는 것은 아니다.
        assertFalse(new CraftGoal("STONE_PICKAXE", 1).isAchieved(placed.build()));
        assertTrue(new CraftGoal("TORCH", 4).isAchieved(state().item("TORCH", 4).build()));
    }

    @Test
    void reachHomeByDistanceOrBeingInside() {
        ReachGoal home = ReachGoal.home(8.0);
        ObservationFixture fixture = state();
        // 거점이 없으면 이룰 수도, 시작할 수도 없다.
        assertFalse(home.isAchieved(fixture.build()));
        assertFalse(home.canAttempt(fixture.build()));

        fixture.homeKnown = true;
        fixture.homeDistance = 30.0;
        assertFalse(home.isAchieved(fixture.build()));
        assertTrue(home.canAttempt(fixture.build()));
        fixture.homeDistance = 8.0;
        assertTrue(home.isAchieved(fixture.build()));
        fixture.homeDistance = 30.0;
        fixture.insideHome = true;
        assertTrue(home.isAchieved(fixture.build()));
    }

    @Test
    void reachPositionAndDimension() {
        ReachGoal point = ReachGoal.position(new BlockPoint(10, 64, 10), 2.0);
        ObservationFixture fixture = state();
        assertFalse(point.isAchieved(fixture.build()));
        fixture.x = 10.5;
        fixture.z = 9.5;
        assertTrue(point.isAchieved(fixture.build()));

        ReachGoal nether = ReachGoal.dimension(ReachGoal.Place.NETHER);
        assertFalse(nether.isAchieved(fixture.build()));
        fixture.dimension = PlayerState.NETHER;
        assertTrue(nether.isAchieved(fixture.build()));
        assertThrows(IllegalArgumentException.class, () -> ReachGoal.dimension(ReachGoal.Place.HOME));
    }

    @Test
    void defeatHostileEndsWhenNothingIsLeftToFight() {
        DefeatGoal goal = new DefeatGoal(DefeatGoal.HOSTILE, 1);
        ObservationFixture fixture = state();
        assertTrue(goal.isAchieved(fixture.build()));
        fixture.hostileNearby = true;
        fixture.combat = "FIGHT";
        assertFalse(goal.isAchieved(fixture.build()));
        // 몬스터가 물러났어도 아직 싸우기로 한 상대가 있으면 끝난 것이 아니다.
        fixture.hostileNearby = false;
        assertFalse(goal.isAchieved(fixture.build()));
    }

    @Test
    void defeatDragonAndUncountedTargets() {
        DefeatGoal dragon = new DefeatGoal(DefeatGoal.ENDER_DRAGON, 1);
        ObservationFixture fixture = state();
        assertFalse(dragon.isAchieved(fixture.build()));
        fixture.dragonDefeated = true;
        assertTrue(dragon.isAchieved(fixture.build()));
        // 잡은 수를 세지 않는 대상은 이뤘다고 하지 않는다.
        assertFalse(new DefeatGoal("BLAZE", 6).isAchieved(fixture.build()));
    }

    @Test
    void buildFollowsWorldProgress() {
        ObservationFixture fixture = state();
        assertFalse(new BuildGoal(BuildGoal.Structure.SHELTER).isAchieved(fixture.build()));
        fixture.shelterBuilt = true;
        assertTrue(new BuildGoal(BuildGoal.Structure.SHELTER).isAchieved(fixture.build()));
        assertFalse(new BuildGoal(BuildGoal.Structure.NETHER_PORTAL).isAchieved(fixture.build()));
    }

    @Test
    void surviveConditions() {
        ObservationFixture fixture = state();
        SurviveGoal escape = new SurviveGoal(SurviveGoal.Reason.ESCAPE_DANGER);
        assertTrue(escape.isAchieved(fixture.build()));
        fixture.inLava = true;
        assertFalse(escape.isAchieved(fixture.build()));
        fixture.inLava = false;
        // 위험한 자리에서 나왔어도 달아나는 중이면 아직 벗어난 것이 아니다.
        fixture.combat = EnvironmentState.FLEE;
        assertFalse(escape.isAchieved(fixture.build()));

        fixture.hungry = true;
        assertFalse(new SurviveGoal(SurviveGoal.Reason.EAT).isAchieved(fixture.build()));
        fixture.healthState = "CRITICAL";
        assertFalse(new SurviveGoal(SurviveGoal.Reason.RECOVER_HEALTH).isAchieved(fixture.build()));
    }

    @Test
    void sleepNeedsABedAndEndsWithTheNight() {
        InteractGoal sleep = new InteractGoal(InteractGoal.Interaction.SLEEP);
        ObservationFixture fixture = state();
        fixture.night = true;
        assertFalse(sleep.isAchieved(fixture.build()));
        assertFalse(sleep.canAttempt(fixture.build()));
        fixture.canSleep = true;
        assertTrue(sleep.canAttempt(fixture.build()));
        fixture.night = false;
        assertTrue(sleep.isAchieved(fixture.build()));
    }

    // 끝이 없는 목표와, 값으로 적지 못한 목표는 스스로 "이뤘다"고 하지 않는다.
    @Test
    void openEndedGoalsNeverReportAchieved() {
        assertFalse(new ExploreGoal().isAchieved(state().build()));
        Goal cleanUp = LegacyGoalAdapter.toGoal(GoalType.CLEAN_INVENTORY);
        assertEquals(GoalCategory.LEGACY, cleanUp.category());
        assertFalse(cleanUp.isAchieved(state().build()));
    }
}
