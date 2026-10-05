package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.build.BlockRole;
import me.herry.minecraftAI.ai.build.Blueprint;
import me.herry.minecraftAI.ai.build.Blueprints;
import me.herry.minecraftAI.ai.goal.model.BuildGoal;
import me.herry.minecraftAI.ai.goal.model.Goal;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 네더 포탈을 짓는 목표와 그 설계도.
 */
class PortalGoalsTest {
    private final GoalSystem goals = new GoalSystem();

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 흑요석 10개와 부싯돌과 부시를 갖춘 AI
    private static Situation readyToBuild() {
        Situation situation = new Situation();
        situation.stage = Stage.NETHER_ENTRY;
        situation.nextMilestone = Milestone.NETHER_PORTAL;
        situation.need = Situation.Need.OTHER;
        situation.hasPickaxe = true;
        situation.flintAndSteel = true;
        situation.foodCount = GoalSystem.TRIP_FOOD;
        situation.plankEquivalent = GoalSystem.TRIP_WOOD;
        return situation;
    }

    @Test
    void buildsThePortalWhenTheMaterialsAreReady() {
        assertEquals(GoalType.BUILD_PORTAL, select(readyToBuild()));
    }

    // 부싯돌과 부시가 없으면 틀을 지어도 불을 붙일 수 없다.
    @Test
    void needsFlintAndSteel() {
        Situation situation = readyToBuild();
        situation.flintAndSteel = false;
        assertNotEquals(GoalType.BUILD_PORTAL, select(situation));
    }

    // 지상에서 하는 일이라 땅속에서 밤을 나는 동안에는 올라가지 않는다.
    @Test
    void waitsForMorningWhenUnderground() {
        Situation situation = readyToBuild();
        situation.underground = true;
        situation.surfaceTooLate = true;
        assertNotEquals(GoalType.BUILD_PORTAL, select(situation));
    }

    @Test
    void automationReachesThePortal() {
        assertTrue(Milestone.NETHER_PORTAL.isAutomated());
        assertFalse(Milestone.BLAZE_RODS.isAutomated());
    }

    @Test
    void mapsToBuildingANetherPortal() {
        Goal goal = LegacyGoalAdapter.toGoal(GoalType.BUILD_PORTAL);
        BuildGoal build = assertInstanceOf(BuildGoal.class, goal);
        assertEquals(BuildGoal.Structure.NETHER_PORTAL, build.structure());
        assertEquals(GoalType.BUILD_PORTAL, LegacyGoalAdapter.toLegacy(goal));
    }

    // 틀은 흑요석 10개와 모서리 4개다. 안쪽 2x3 은 비운다.
    @Test
    void blueprintUsesTenObsidianAndFourCorners() {
        Blueprint portal = Blueprints.portal();
        assertEquals(Milestone.PORTAL_OBSIDIAN, portal.count(BlockRole.FRAME));
        assertEquals(4, portal.count(BlockRole.WALL));
        assertSame(portal, Blueprints.byName(Blueprints.PORTAL));

        Set<String> inside = new HashSet<>();
        for (Blueprint.Part part : portal.partsOf(BlockRole.CLEAR)) {
            if (part.dz() == -1) inside.add(part.dx() + "," + part.dy());
        }
        assertEquals(Set.of("0,1", "1,1", "0,2", "1,2", "0,3", "1,3"), inside);
    }

    /**
     * 블록은 이미 있는 블록에 붙여야 놓을 수 있다. 틀의 칸은 놓는 순서대로, 바닥이나 먼저 놓인 틀의 칸과 맞닿아 있어야 한다.
     */
    @Test
    void everyFramePartTouchesSomethingPlacedBefore() {
        Set<String> placed = new HashSet<>();
        List<Blueprint.Part> parts = Blueprints.portal().parts();
        for (Blueprint.Part part : parts) {
            if (part.role() == BlockRole.CLEAR) continue;
            if (part.role() == BlockRole.FLOOR) {
                placed.add(key(part.dx(), part.dy(), part.dz()));
                continue;
            }
            boolean supported = placed.contains(key(part.dx(), part.dy() - 1, part.dz()))
                    || placed.contains(key(part.dx() - 1, part.dy(), part.dz()))
                    || placed.contains(key(part.dx() + 1, part.dy(), part.dz()))
                    || placed.contains(key(part.dx(), part.dy() + 1, part.dz()));
            assertTrue(supported, "받침이 없는 칸: " + part);
            placed.add(key(part.dx(), part.dy(), part.dz()));
        }
    }

    // 기준점에 서서 틀의 모든 칸에 손이 닿아야 한다.
    @Test
    void wholeFrameIsWithinReachOfTheOrigin() {
        for (Blueprint.Part part : Blueprints.portal().parts()) {
            double dx = part.dx();
            double dy = part.dy() + 0.5 - 1.62;
            double dz = part.dz();
            assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) <= 4.5, "손이 닿지 않는 칸: " + part);
        }
    }

    private static String key(int dx, int dy, int dz) {
        return dx + "," + dy + "," + dz;
    }
}
