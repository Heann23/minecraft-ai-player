package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 용암 호수 가장자리에서 흑요석을 만들 자리 고르기.
 */
class ObsidianSiteTest {
    // 호수의 표면은 땅의 맨 위 칸과 같은 높이다.
    private static final int SURFACE = GridTerrain.GROUND - 1;

    private final GridTerrain terrain = new GridTerrain();
    private final Set<BlockPoint> sources = new HashSet<>();
    private final Predicate<BlockPoint> isLava = sources::contains;

    // x 는 fromX~toX, z 는 fromZ~toZ 인 용암 호수 (깊이 한 칸)
    private void lake(int fromX, int toX, int fromZ, int toZ) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                terrain.set(x, SURFACE, z, BlockClass.DANGER);
                sources.add(new BlockPoint(x, SURFACE, z));
            }
        }
    }

    private ObsidianSite find(BlockPoint lava) {
        return ObsidianSite.find(terrain, isLava, lava, 6, stand -> false);
    }

    @Test
    void picksARimBlockWithAPourSpotBesideIt() {
        lake(10, 14, 10, 14);
        ObsidianSite site = find(new BlockPoint(12, SURFACE, 12));
        assertNotNull(site);
        // 둑은 호수 바로 옆의 단단한 블록이고, 호수 쪽 옆 칸은 용암이다.
        assertEquals(BlockClass.SOLID, terrain.classify(site.stand().x(), site.stand().y(), site.stand().z()));
        assertTrue(isLava.test(site.stand().offset(site.lakeX(), 0, site.lakeZ())));
        // 물을 부을 곳은 둑의 옆이고, 그것도 호수와 맞닿아 있다.
        assertEquals(1.0, site.pour().distance(site.stand()), 1.0E-9);
        assertTrue(isLava.test(site.pour().offset(site.lakeX(), 0, site.lakeZ())));
        assertTrue(site.cells() >= 10, "한 자리에서 포탈에 쓸 만큼 닿아야 한다: " + site.cells());
    }

    // 같은 지형이면 몇 번을 물어도 같은 자리가 나와야 한다. 걸어가는 동안 자리가 바뀌면 안 된다.
    @Test
    void sameTerrainGivesTheSameSite() {
        lake(10, 14, 10, 14);
        ObsidianSite first = find(new BlockPoint(12, SURFACE, 12));
        ObsidianSite again = find(new BlockPoint(12, SURFACE, 12));
        assertEquals(first, again);
    }

    // 모서리에서는 손이 닿는 용암이 적다. 변의 가운데를 고른다.
    @Test
    void prefersTheMiddleOfAnEdge() {
        lake(10, 14, 10, 14);
        ObsidianSite site = find(new BlockPoint(12, SURFACE, 12));
        assertNotNull(site);
        boolean alongX = site.lakeX() != 0;
        int along = alongX ? site.stand().z() : site.stand().x();
        assertTrue(along >= 11 && along <= 13, "가운데 쪽이어야 한다: " + site.stand());
    }

    // 둑 뒤에 다른 용암이 있으면 쓰지 않는다. 퍼진 물이 닿아서 조약돌이 생기거나 그 용암이 밀려온다.
    @Test
    void rejectsARimWithLavaBehindIt() {
        lake(10, 10, 10, 14);
        // 호수의 양쪽 둑 뒤(x 8 과 x 12)가 모두 용암이다.
        for (int z = 9; z <= 15; z++) {
            terrain.set(8, SURFACE, z, BlockClass.DANGER);
            terrain.set(12, SURFACE, z, BlockClass.DANGER);
        }
        ObsidianSite site = find(new BlockPoint(10, SURFACE, 12));
        // 남는 것은 호수의 짧은 변(z 9, z 15)인데, 거기에는 옆에서 호수와 맞닿는 물 붓는 자리가 없다.
        assertNull(site);
    }

    // 천장이 낮으면 받침 위에 설 수 없다.
    @Test
    void needsRoomForThePedestalAndTheBody() {
        lake(10, 14, 10, 14);
        for (int x = 6; x <= 18; x++) {
            for (int z = 6; z <= 18; z++) terrain.set(x, SURFACE + 3, z, BlockClass.SOLID);
        }
        assertNull(find(new BlockPoint(12, SURFACE, 12)));
    }

    // 이미 받침을 놓고 그 위에 서 있을 때도 같은 자리로 알아봐야 한다.
    @Test
    void recognisesTheSiteFromOnTopOfThePedestal() {
        lake(10, 14, 10, 14);
        ObsidianSite site = find(new BlockPoint(12, SURFACE, 12));
        assertNotNull(site);
        terrain.set(site.stand().x(), SURFACE + 1, site.stand().z(), BlockClass.SOLID);
        assertNull(ObsidianSite.at(terrain, isLava, site.stand(), false));
        ObsidianSite onTop = ObsidianSite.at(terrain, isLava, site.stand(), true);
        assertNotNull(onTop);
        assertEquals(site.pour(), onTop.pour());
    }

    @Test
    void skipsUsedSites() {
        lake(10, 14, 10, 14);
        ObsidianSite first = find(new BlockPoint(12, SURFACE, 12));
        assertNotNull(first);
        ObsidianSite second = ObsidianSite.find(terrain, isLava, new BlockPoint(12, SURFACE, 12), 6, first.stand()::equals);
        assertNotNull(second);
        assertFalse(second.stand().equals(first.stand()));
    }

    // 손이 닿는 칸은 눈에서 가까운 순서이고, 닿지 않는 먼 칸은 들어 있지 않다.
    @Test
    void reachableCellsAreSortedAndWithinReach() {
        lake(10, 14, 10, 14);
        BlockPoint stand = new BlockPoint(9, SURFACE, 12);
        List<BlockPoint> cells = ObsidianSite.reachable(stand, isLava, terrain);
        assertFalse(cells.isEmpty());
        assertEquals(new BlockPoint(10, SURFACE, 12), cells.getFirst());
        for (BlockPoint cell : cells) assertTrue(ObsidianSite.eyeDistance(stand, cell) <= ObsidianSite.REACH);
        assertFalse(cells.contains(new BlockPoint(14, SURFACE, 12)));
    }
}
