package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 나무 위에서 발밑을 캐고 내려와도 되는지. 시드 437123886 에서 나무 꼭대기에 스폰된 AI 가 13분 동안 내려오지 못했다.
 */
class PerchRulesTest {
    private static final int GROUND = GridTerrain.GROUND;

    // (0, 0) 에 땅에서 height 칸 떠 있는 잎 한 칸을 두고 그 위에 선다.
    private static BlockPoint standOnLeaf(GridTerrain terrain, int height) {
        terrain.set(0, GROUND + height, 0, BlockClass.SOLID);
        return new BlockPoint(0, GROUND + height + 1, 0);
    }

    // 줄기 꼭대기에서는 한 칸씩 내려온다.
    @Test
    void stepsDownOneBlockOnATrunk() {
        GridTerrain terrain = new GridTerrain().column(0, 0, GROUND, GROUND + 5, BlockClass.SOLID);
        PerchRules.Landing landing = PerchRules.landingBelow(terrain, new BlockPoint(0, GROUND + 6, 0));
        assertNotNull(landing);
        assertEquals(1, landing.drop());
        assertEquals(0, landing.damage());
        assertTrue(PerchRules.isAcceptable(landing, 1.0));
    }

    // 잎 아래가 비어 있어도 세 칸까지는 다치지 않는다.
    @Test
    void dropsThroughTheCanopyWithoutDamage() {
        GridTerrain terrain = new GridTerrain();
        PerchRules.Landing landing = PerchRules.landingBelow(terrain, standOnLeaf(terrain, 2));
        assertNotNull(landing);
        assertEquals(3, landing.drop());
        assertEquals(0, landing.damage());
    }

    // 조금 다치는 높이는 체력이 넉넉할 때만 뛰어내린다.
    @Test
    void acceptsSmallDamageOnlyWhenHealthy() {
        GridTerrain terrain = new GridTerrain();
        PerchRules.Landing landing = PerchRules.landingBelow(terrain, standOnLeaf(terrain, 5));
        assertNotNull(landing);
        assertEquals(6, landing.drop());
        assertEquals(3, landing.damage());
        assertTrue(PerchRules.isAcceptable(landing, 20.0));
        assertFalse(PerchRules.isAcceptable(landing, 12.0));
    }

    // 크게 다칠 높이에서는 내려오지 않는다.
    @Test
    void refusesADeepFall() {
        GridTerrain terrain = new GridTerrain();
        assertNull(PerchRules.landingBelow(terrain, standOnLeaf(terrain, 12)));
    }

    // 물 위로는 높아도 다치지 않는다.
    @Test
    void waterBreaksTheFall() {
        GridTerrain terrain = new GridTerrain().set(0, GROUND, 0, BlockClass.WATER);
        PerchRules.Landing landing = PerchRules.landingBelow(terrain, standOnLeaf(terrain, 5));
        assertNotNull(landing);
        assertTrue(landing.soft());
        assertEquals(0, landing.damage());
        assertTrue(PerchRules.isAcceptable(landing, 4.0));
    }

    // 용암 위로는 내려오지 않는다.
    @Test
    void neverDropsIntoDanger() {
        GridTerrain terrain = new GridTerrain().set(0, GROUND, 0, BlockClass.DANGER);
        assertNull(PerchRules.landingBelow(terrain, standOnLeaf(terrain, 2)));
    }
}
