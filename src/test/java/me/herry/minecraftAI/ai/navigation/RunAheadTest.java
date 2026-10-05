package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 달리면서 점프를 섞어도 되는 구간인지에 대한 판단.
 */
class RunAheadTest {
    private static final int Y = GridTerrain.GROUND;

    // (0, 0) 에서 +x 쪽으로 곧게 뻗은 경로. 경로의 첫 칸은 (1, 0) 이다 (출발 칸은 경로에 들지 않는다).
    private static Path straight(int length) {
        List<BlockPoint> points = new ArrayList<>();
        for (int x = 1; x <= length; x++) points.add(new BlockPoint(x, Y, 0));
        return new Path(points, false);
    }

    @Test
    void runsOnOpenFlatGround() {
        assertTrue(RunAhead.isClear(new GridTerrain(), straight(12), 1));
        assertTrue(RunAhead.isClear(new GridTerrain(), straight(12), 5));
    }

    // 점프 한 번에 네 칸 가까이 날아가므로, 그만큼 곧은 길이 남아 있을 때만 뛴다.
    @Test
    void doesNotJumpWhenThePathIsAboutToEnd() {
        Path path = straight(8);
        assertTrue(RunAhead.isClear(new GridTerrain(), path, 8 - RunAhead.LENGTH));
        assertFalse(RunAhead.isClear(new GridTerrain(), path, 8 - RunAhead.LENGTH + 1));
    }

    // 출발하자마자는 어느 쪽으로 달려왔는지 알 수 없다.
    @Test
    void doesNotJumpAtTheVeryStart() {
        assertFalse(RunAhead.isClear(new GridTerrain(), straight(12), 0));
    }

    @Test
    void doesNotJumpIntoATurn() {
        List<BlockPoint> points = new ArrayList<>();
        for (int x = 1; x <= 5; x++) points.add(new BlockPoint(x, Y, 0));
        for (int z = 1; z <= 6; z++) points.add(new BlockPoint(5, Y, z));
        Path path = new Path(points, false);

        // 꺾이는 칸까지 다섯 칸이 안 남았으면 뛰지 않는다. 꺾이는 칸으로 가는 동안에도 뛰지 않는다.
        assertFalse(RunAhead.isClear(new GridTerrain(), path, 1));
        assertFalse(RunAhead.isClear(new GridTerrain(), path, 4));
        // 꺾이는 칸을 밟은 뒤에는 새 방향으로 곧은 길이 다섯 칸 있으므로 다시 뛴다 (몸이 그쪽으로 돌아선 뒤에).
        assertTrue(RunAhead.isClear(new GridTerrain(), path, 5));
    }

    @Test
    void runsDiagonally() {
        List<BlockPoint> points = new ArrayList<>();
        for (int i = 1; i <= 10; i++) points.add(new BlockPoint(i, Y, i));
        assertTrue(RunAhead.isClear(new GridTerrain(), new Path(points, false), 2));
    }

    @Test
    void doesNotJumpOnStairs() {
        GridTerrain terrain = new GridTerrain();
        List<BlockPoint> points = new ArrayList<>();
        for (int x = 1; x <= 3; x++) points.add(new BlockPoint(x, Y, 0));
        // x = 4 부터 땅이 한 칸 높다.
        for (int x = 4; x <= 12; x++) {
            for (int z = -2; z <= 2; z++) terrain.set(x, Y, z, BlockClass.SOLID);
            points.add(new BlockPoint(x, Y + 1, 0));
        }
        Path path = new Path(points, false);

        assertFalse(RunAhead.isClear(terrain, path, 1));
        // 올라선 뒤의 평지에서는 다시 뛴다.
        assertTrue(RunAhead.isClear(terrain, path, 5));
    }

    // 굴이나 나무 아래처럼 머리 위가 낮으면 뛰어도 머리만 부딪힌다.
    @Test
    void doesNotJumpUnderALowCeiling() {
        GridTerrain terrain = new GridTerrain();
        terrain.set(4, Y + 2, 0, BlockClass.SOLID);
        assertFalse(RunAhead.isClear(terrain, straight(12), 1));
        // 낮은 곳을 지난 뒤에는 뛴다.
        assertTrue(RunAhead.isClear(terrain, straight(12), 5));
    }

    // 조금 빗나가 옆 칸에 내려도 안전해야 한다. 낭떠러지, 물, 용암 옆의 좁은 길에서는 뛰지 않는다.
    @Test
    void doesNotJumpAlongAnEdge() {
        GridTerrain cliff = new GridTerrain();
        cliff.column(3, 1, Y - 3, Y - 1, BlockClass.OPEN);
        assertFalse(RunAhead.isClear(cliff, straight(12), 1));

        GridTerrain water = new GridTerrain();
        water.set(3, Y - 1, -1, BlockClass.WATER);
        assertFalse(RunAhead.isClear(water, straight(12), 1));

        GridTerrain lava = new GridTerrain();
        lava.set(3, Y - 1, 1, BlockClass.DANGER);
        assertFalse(RunAhead.isClear(lava, straight(12), 1));
    }

    // 옆이 벽이면 그쪽으로는 빗나갈 수 없으므로 뛰어도 된다 (머리 위가 트인 골목).
    @Test
    void runsAlongAWall() {
        GridTerrain terrain = new GridTerrain();
        for (int x = 0; x <= 12; x++) terrain.column(x, 1, Y, Y + 3, BlockClass.SOLID);
        assertTrue(RunAhead.isClear(terrain, straight(12), 1));
    }

    @Test
    void doesNotJumpIntoCobweb() {
        GridTerrain terrain = new GridTerrain();
        terrain.set(4, Y, 0, BlockClass.WEB);
        assertFalse(RunAhead.isClear(terrain, straight(12), 1));
    }
}
