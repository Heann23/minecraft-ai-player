package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AStarSearchTest {
    private static final int Y = GridTerrain.GROUND;
    private static final int MAX_NODES = 4000;
    private static final int MAX_DISTANCE = 64;
    private static final int MAX_DROP = 3;

    private static AStarSearch search(TerrainView terrain, BlockPoint start, PathGoal goal) {
        return new AStarSearch(terrain, start, goal, MAX_NODES, MAX_DISTANCE, MAX_DROP);
    }

    private static AStarSearch.State run(AStarSearch search) {
        AStarSearch.State state = AStarSearch.State.RUNNING;
        // 탐색이 반드시 끝나는지도 함께 확인한다.
        for (int i = 0; i < 1000 && state == AStarSearch.State.RUNNING; i++) state = search.advance(100);
        assertNotEquals(AStarSearch.State.RUNNING, state, "search must terminate");
        return state;
    }

    private static boolean passesThrough(Path path, int x, int z) {
        return path.points().stream().anyMatch(point -> point.x() == x && point.z() == z);
    }

    @Test
    void findsStraightPathOnFlatGround() {
        AStarSearch search = search(new GridTerrain(), new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(10, Y, 0), 0.5));

        assertEquals(AStarSearch.State.FOUND, run(search));
        Path path = search.getPath();
        assertFalse(path.partial());
        assertEquals(10, path.size());
        assertEquals(new BlockPoint(10, Y, 0), path.get(path.size() - 1));
    }

    @Test
    void walksAroundWall() {
        GridTerrain terrain = new GridTerrain();
        // x = 5 에 z = -3..3 범위로 2칸 높이의 벽을 세운다.
        for (int z = -3; z <= 3; z++) terrain.column(5, z, Y, Y + 1, BlockClass.SOLID);

        AStarSearch search = search(terrain, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(10, Y, 0), 0.5));

        assertEquals(AStarSearch.State.FOUND, run(search));
        for (BlockPoint point : search.getPath().points()) {
            assertFalse(point.x() == 5 && Math.abs(point.z()) <= 3, "path must not pass through the wall: " + point);
        }
    }

    @Test
    void stepsUpOneBlock() {
        GridTerrain terrain = new GridTerrain();
        // x >= 3 부터 땅이 한 칸 높다.
        for (int x = 3; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) terrain.set(x, Y, z, BlockClass.SOLID);
        }

        AStarSearch search = search(terrain, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(6, Y + 1, 0), 0.5));

        assertEquals(AStarSearch.State.FOUND, run(search));
        assertEquals(new BlockPoint(6, Y + 1, 0), search.getPath().get(search.getPath().size() - 1));
    }

    @Test
    void cannotClimbTwoBlockLedge() {
        GridTerrain terrain = new GridTerrain();
        for (int x = 3; x <= 8; x++) {
            for (int z = -20; z <= 20; z++) terrain.column(x, z, Y, Y + 1, BlockClass.SOLID);
        }

        AStarSearch search = new AStarSearch(terrain, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(6, Y + 2, 0), 0.5), MAX_NODES, 16, MAX_DROP);

        assertNotEquals(AStarSearch.State.FOUND, run(search));
    }

    @Test
    void dropsDownSafeHeightButNotCliff() {
        GridTerrain safe = new GridTerrain();
        GridTerrain cliff = new GridTerrain();
        // x >= 3 부터 땅이 꺼져 있다. 한쪽은 3칸, 다른 쪽은 6칸 깊이다.
        for (int x = 3; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                safe.column(x, z, Y - 3, Y - 1, BlockClass.OPEN);
                cliff.column(x, z, Y - 6, Y - 1, BlockClass.OPEN);
            }
        }

        AStarSearch down = search(safe, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(6, Y - 3, 0), 0.5));
        assertEquals(AStarSearch.State.FOUND, run(down));

        AStarSearch tooDeep = new AStarSearch(cliff, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(6, Y - 6, 0), 0.5), MAX_NODES, 12, MAX_DROP);
        assertNotEquals(AStarSearch.State.FOUND, run(tooDeep));
    }

    @Test
    void avoidsLava() {
        GridTerrain terrain = new GridTerrain();
        // 직선 경로 위의 땅 한 줄을 용암으로 바꾼다.
        for (int z = -2; z <= 2; z++) terrain.set(5, Y - 1, z, BlockClass.DANGER);

        AStarSearch search = search(terrain, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(10, Y, 0), 0.5));

        assertEquals(AStarSearch.State.FOUND, run(search));
        for (int z = -2; z <= 2; z++) assertFalse(passesThrough(search.getPath(), 5, z), "path must not cross lava");
    }

    @Test
    void doesNotCutCorners() {
        GridTerrain terrain = new GridTerrain();
        terrain.column(1, 0, Y, Y + 1, BlockClass.SOLID);

        AStarSearch search = search(terrain, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(1, Y, 1), 0.1));

        assertEquals(AStarSearch.State.FOUND, run(search));
        // 대각선으로 바로 가면 (1, 0) 의 블록 모서리를 뚫게 되므로 (0, 1) 을 거쳐야 한다.
        assertEquals(2, search.getPath().size());
        assertEquals(new BlockPoint(0, Y, 1), search.getPath().get(0));
    }

    @Test
    void reachGoalStopsNextToBlock() {
        GridTerrain terrain = new GridTerrain();
        BlockPoint log = new BlockPoint(10, Y, 0);
        terrain.column(10, 0, Y, Y + 4, BlockClass.SOLID);

        AStarSearch search = search(terrain, new BlockPoint(0, Y, 0), PathGoal.reach(log, 4.0));

        assertEquals(AStarSearch.State.FOUND, run(search));
        BlockPoint end = search.getPath().get(search.getPath().size() - 1);
        assertNotEquals(log, end);
        assertTrue(PathGoal.reach(log, 4.0).reached(end.x(), end.y(), end.z()));
    }

    @Test
    void returnsPartialPathWhenGoalIsEnclosed() {
        GridTerrain terrain = new GridTerrain();
        // 목표 지점을 벽으로 완전히 둘러싼다.
        for (int x = 9; x <= 11; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 10 || z != 0) terrain.column(x, z, Y, Y + 2, BlockClass.SOLID);
            }
        }
        terrain.set(10, Y + 2, 0, BlockClass.SOLID);

        AStarSearch search = new AStarSearch(terrain, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(10, Y, 0), 0.5), 1500, 16, MAX_DROP);

        assertEquals(AStarSearch.State.PARTIAL, run(search));
        assertTrue(search.getPath().partial());
        assertTrue(search.getExpandedNodes() <= 1500, "node limit must be respected");
    }

    @Test
    void failsWhenStartIsEnclosed() {
        GridTerrain terrain = new GridTerrain();
        for (int[] side : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            terrain.column(side[0], side[1], Y, Y + 3, BlockClass.SOLID);
        }

        AStarSearch search = search(terrain, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(10, Y, 0), 0.5));

        assertEquals(AStarSearch.State.FAILED, run(search));
    }

    @Test
    void advanceRespectsBudget() {
        AStarSearch search = search(new GridTerrain(), new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(40, Y, 30), 0.5));

        assertEquals(AStarSearch.State.RUNNING, search.advance(5));
        assertEquals(5, search.getExpandedNodes());
        assertEquals(AStarSearch.State.FOUND, run(search));
    }

    @Test
    void unloadedTerrainIsNotWalkable() {
        GridTerrain terrain = new GridTerrain();
        for (int x = 4; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) terrain.column(x, z, Y - 1, Y + 1, BlockClass.UNLOADED);
        }

        AStarSearch search = new AStarSearch(terrain, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(10, Y, 0), 0.5), MAX_NODES, 20, MAX_DROP);

        assertEquals(AStarSearch.State.PARTIAL, run(search));
        for (BlockPoint point : search.getPath().points()) assertTrue(point.x() < 4, "must stop before unloaded area");
    }

    @Test
    void swimsAcrossWater() {
        GridTerrain terrain = new GridTerrain();
        // x = 3..6 은 깊이 2칸의 물이고, 양옆으로 돌아갈 길은 없다.
        for (int x = 3; x <= 6; x++) {
            for (int z = -20; z <= 20; z++) terrain.column(x, z, Y - 2, Y - 1, BlockClass.WATER);
        }

        AStarSearch search = new AStarSearch(terrain, new BlockPoint(0, Y, 0), PathGoal.arrive(new BlockPoint(10, Y, 0), 0.5), MAX_NODES, 20, MAX_DROP);

        assertEquals(AStarSearch.State.FOUND, run(search));
    }
}
