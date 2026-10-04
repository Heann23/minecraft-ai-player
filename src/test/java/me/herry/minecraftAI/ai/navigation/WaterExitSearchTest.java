package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WaterExitSearchTest {
    private static final BlockPoint START = new BlockPoint(0, 62, 0);
    private static final BlockPoint CEILING = new BlockPoint(0, 64, 0);
    private static final BlockPoint SHORE = new BlockPoint(0, 63, 1);

    private static final class Pool implements TerrainView {
        private final Map<BlockPoint, BlockClass> blocks = new HashMap<>();

        Pool() {
            set(START, BlockClass.WATER);
            set(START.offset(0, 1, 0), BlockClass.OPEN);
            set(SHORE.offset(0, 1, 0), BlockClass.OPEN);
            set(SHORE.offset(0, 2, 0), BlockClass.OPEN);
        }

        void set(BlockPoint point, BlockClass type) { blocks.put(point, type); }

        @Override
        public BlockClass classify(int x, int y, int z) {
            return blocks.getOrDefault(new BlockPoint(x, y, z), BlockClass.SOLID);
        }
    }

    @Test
    void clearsBothTheLowCeilingAndHighBankWhileKeepingTheLandingFloor() {
        Pool pool = new Pool();
        AStarSearch blocked = new AStarSearch(pool, START, PathGoal.arrive(SHORE.offset(0, 1, 0), 0.3), 128, 6, 3);
        assertNotEquals(AStarSearch.State.FOUND, blocked.advance(128));

        WaterExitSearch.Exit exit = WaterExitSearch.find(pool, START, block -> List.of(CEILING, SHORE).contains(block));
        assertNotNull(exit);
        assertEquals(SHORE, exit.feet());
        assertEquals(List.of(CEILING, SHORE), exit.blocksToBreak());
        assertFalse(exit.blocksToBreak().contains(SHORE.offset(0, -1, 0)));
    }

    @Test
    void usesAnExistingReachableLowBankWithoutDigging() {
        Pool pool = new Pool();
        pool.set(CEILING, BlockClass.OPEN);
        pool.set(SHORE, BlockClass.OPEN);
        WaterExitSearch.Exit exit = WaterExitSearch.find(pool, START, block -> false);
        assertNotNull(exit);
        assertEquals(SHORE, exit.feet());
        assertTrue(exit.blocksToBreak().isEmpty());
    }

    @Test
    void refusesAnUnbreakableOrProtectedCeilingInsteadOfChoosingAnUnreachableBank() {
        assertNull(WaterExitSearch.find(new Pool(), START, block -> block.equals(SHORE)));
    }

    @Test
    void refusesToOpenAWallBesideLava() {
        Pool pool = new Pool();
        pool.set(SHORE.offset(1, 0, 0), BlockClass.DANGER);
        assertNull(WaterExitSearch.find(pool, START, block -> List.of(CEILING, SHORE).contains(block)));
    }

    @Test
    void refusesToDigIntoUnloadedTerrainOrWaterThatWouldFloodTheExit() {
        for (BlockClass unsafe : List.of(BlockClass.UNLOADED, BlockClass.WATER)) {
            Pool pool = new Pool();
            pool.set(SHORE.offset(1, 0, 0), unsafe);
            assertNull(WaterExitSearch.find(pool, START, block -> List.of(CEILING, SHORE).contains(block)));
        }
    }

    @Test
    void doesNotDigABankBelowTheWaterSurface() {
        Pool pool = new Pool();
        pool.set(START.offset(0, 1, 0), BlockClass.WATER);
        assertNull(WaterExitSearch.find(pool, START, block -> true));
    }
}
