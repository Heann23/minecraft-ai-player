package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaftAccessTest {
    @Test
    void doesNotTreatANearbyIntactShaftAboveAnUnclimbableLedgeAsAccessible() {
        TerrainView ledge = (x, y, z) -> y < 64 + (x >= 3 ? 3 : 0) ? BlockClass.SOLID : BlockClass.OPEN;
        PathGoal shaft = PathGoal.arrive(new BlockPoint(4, 67, 0), 1.5);
        assertFalse(ShaftAccess.canReach(ledge, new BlockPoint(0, 64, 0), shaft));
    }

    @Test
    void usesAnExistingRouteWhenTheShaftCanBeReachedBySteps() {
        TerrainView stair = (x, y, z) -> y < 64 + Math.min(3, Math.max(0, x)) ? BlockClass.SOLID : BlockClass.OPEN;
        assertTrue(ShaftAccess.canReach(stair, new BlockPoint(0, 64, 0), PathGoal.arrive(new BlockPoint(4, 67, 0), 1.5)));
    }

    // 회귀: 계단 바닥 (-28,62,241) 에서 (-29,62,241) → (-29,62,242) → (-29,62,243) 으로 걸어간 자리에서 새 계단을 팠다.
    // 사이의 두 칸은 굴로 기록되지 않아서 보호받지 못했고, 새 계단이 그 바닥을 파내서 올라가는 길이 끊겼다.
    // 굴의 끝과 다시 파기 시작하는 자리를 잇는 칸을 찾아내야 한다.
    @Test
    void findsTheCellsThatLinkTheShaftEndToWhereDiggingResumes() {
        GridTerrain pit = new GridTerrain();
        // 땅속(y 62~63)에 사람이 지나갈 높이로 뚫린 ㄱ자 통로. 나머지는 돌이다.
        int[][] walkway = {{-28, 241}, {-29, 241}, {-29, 242}, {-29, 243}};
        for (int x = -32; x <= -25; x++) {
            for (int z = 238; z <= 246; z++) pit.column(x, z, 62, 70, BlockClass.SOLID);
        }
        for (int[] cell : walkway) pit.column(cell[0], cell[1], 62, 63, BlockClass.OPEN);

        BlockPoint tail = new BlockPoint(-28, 62, 241);
        BlockPoint resume = new BlockPoint(-29, 62, 243);
        assertTrue(ShaftAccess.leavesGap(tail, resume));
        assertEquals(List.of(new BlockPoint(-29, 62, 241), new BlockPoint(-29, 62, 242)), ShaftAccess.link(pit, tail, resume));
    }

    // 굴의 끝에 서 있거나 바로 다음 단이면 사이에 낀 길이 없다. 멀리 떨어진 곳(지상 등)은 그 굴과 이어 붙이지 않는다.
    @Test
    void linksOnlyWhenDiggingResumesAFewCellsFromTheShaftEnd() {
        BlockPoint tail = new BlockPoint(0, 40, 0);
        assertFalse(ShaftAccess.leavesGap(tail, tail));
        assertFalse(ShaftAccess.leavesGap(tail, new BlockPoint(1, 39, 0)));
        assertFalse(ShaftAccess.leavesGap(tail, new BlockPoint(0, 40, 1)));
        assertTrue(ShaftAccess.leavesGap(tail, new BlockPoint(1, 40, 1)));
        assertTrue(ShaftAccess.leavesGap(tail, new BlockPoint(3, 40, 0)));
        assertFalse(ShaftAccess.leavesGap(tail, new BlockPoint(9, 40, 0)));
        // 회귀: 걸어 다닌 칸을 모두 보호했더니, 지상에서는 둘레가 전부 걸어온 칸이라 계단을 어느 쪽으로도 파지 못했다.
        // 굴이 없거나 굴에서 멀면 보호할 길도 없다.
        assertTrue(ShaftAccess.link(new GridTerrain(), null, new BlockPoint(0, 64, 0)).isEmpty());
        assertTrue(ShaftAccess.link(new GridTerrain(), new BlockPoint(0, 24, 0), new BlockPoint(5, 64, 5)).isEmpty());
    }

    // 길이 막혀 있으면 이어 붙일 칸도 없다.
    @Test
    void doesNotLinkAcrossSolidRock() {
        GridTerrain rock = new GridTerrain();
        for (int x = -2; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) rock.column(x, z, 40, 70, BlockClass.SOLID);
        }
        rock.column(0, 0, 40, 41, BlockClass.OPEN).column(4, 0, 40, 41, BlockClass.OPEN);
        assertTrue(ShaftAccess.link(rock, new BlockPoint(0, 40, 0), new BlockPoint(4, 40, 0)).isEmpty());
    }

    @Test
    void doesNotAcceptPartialProgressOrAnUnloadedAccessPoint() {
        GridTerrain terrain = new GridTerrain();
        for (int z = -21; z <= 21; z++) terrain.column(3, z, 64, 66, BlockClass.UNLOADED);
        assertFalse(ShaftAccess.canReach(terrain, new BlockPoint(0, 64, 0), PathGoal.arrive(new BlockPoint(6, 64, 0), 1.5)));
    }
}
