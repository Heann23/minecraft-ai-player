package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefugeSearchTest {
    private static final BlockPoint FEET = new BlockPoint(0, 17, 0);
    private static final int BLOCKS = 16;

    // 온통 돌인 땅속. 빈 곳만 따로 적는다.
    private static final class Rock implements TerrainView {
        private final Map<BlockPoint, BlockClass> blocks = new HashMap<>();

        Rock open(int x1, int y1, int z1, int x2, int y2, int z2) {
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) blocks.put(new BlockPoint(x, y, z), BlockClass.OPEN);
                }
            }
            return this;
        }

        Rock set(BlockPoint point, BlockClass type) {
            blocks.put(point, type);
            return this;
        }

        @Override
        public BlockClass classify(int x, int y, int z) {
            return blocks.getOrDefault(new BlockPoint(x, y, z), BlockClass.SOLID);
        }
    }

    // 동쪽 벽에 붙어 선 동굴 방 (x -6..0, z -3..3, 높이 3칸). 몬스터는 서쪽에서 온다.
    private static Rock caveRoom() {
        return new Rock().open(-6, 17, -3, 0, 19, 3);
    }

    // 회귀: 동굴에서 몬스터 여럿에게 쫓겨 43초 동안 달아나지 못하고 죽었다.
    // 옆 벽을 한 칸 파고 들어가서 들어온 쪽 두 칸을 막으면 4번의 작업으로 숨을 수 있다.
    @Test
    void digsIntoTheWallAwayFromTheMonstersAndSealsTheEntrance() {
        RefugeSearch.Refuge refuge = RefugeSearch.find(caveRoom(), FEET, new BlockPoint(-5, 17, 0), block -> true, BLOCKS);
        assertNotNull(refuge);
        assertEquals(new BlockPoint(1, 17, 0), refuge.cell());
        assertEquals(List.of(new BlockPoint(1, 18, 0), new BlockPoint(1, 17, 0)), refuge.toBreak());
        // 들어온 쪽의 아랫칸을 먼저 막고, 그것을 딛고 윗칸을 막는다.
        assertEquals(List.of(FEET, FEET.offset(0, 1, 0)), refuge.toSeal());
    }

    // 자기가 판 굴의 막다른 끝에서는 팔 필요 없이 뒤쪽 두 칸만 막으면 된다.
    @Test
    void sealsTheTunnelBehindItAtADeadEnd() {
        Rock tunnel = new Rock().open(-8, 17, 0, 0, 18, 0);
        RefugeSearch.Refuge refuge = RefugeSearch.find(tunnel, FEET, new BlockPoint(-6, 17, 0), block -> true, BLOCKS);
        assertNotNull(refuge);
        assertEquals(FEET, refuge.cell());
        assertTrue(refuge.toBreak().isEmpty());
        assertEquals(List.of(new BlockPoint(-1, 17, 0), new BlockPoint(-1, 18, 0)), refuge.toSeal());
    }

    // 넓은 방 한가운데처럼 옆에 벽이 없으면 발밑을 세 칸 파고 들어가서 위를 한 칸 덮는다.
    @Test
    void digsDownAndCoversTheHoleInTheOpen() {
        Rock hall = new Rock().open(-6, 17, -6, 6, 20, 6);
        RefugeSearch.Refuge refuge = RefugeSearch.find(hall, FEET, new BlockPoint(-5, 17, 0), block -> true, BLOCKS);
        assertNotNull(refuge);
        assertEquals(new BlockPoint(0, 14, 0), refuge.cell());
        assertEquals(List.of(new BlockPoint(0, 16, 0), new BlockPoint(0, 15, 0), new BlockPoint(0, 14, 0)), refuge.toBreak());
        assertEquals(List.of(new BlockPoint(0, 16, 0)), refuge.toSeal());
    }

    // 물이나 용암에 닿은 벽, 지나온 굴의 발판처럼 캐면 안 되는 블록으로는 파고들지 않는다.
    @Test
    void doesNotDigIntoWallsThatFloodOrAreProtected() {
        Rock room = caveRoom().set(new BlockPoint(2, 17, 0), BlockClass.WATER);
        BlockPoint threat = new BlockPoint(-5, 17, 0);
        RefugeSearch.Refuge flooded = RefugeSearch.find(room, FEET, threat, block -> true, BLOCKS);
        assertNotNull(flooded);
        assertFalse(flooded.cell().equals(new BlockPoint(1, 17, 0)));

        // 캘 수 있는 블록이 하나도 없으면 숨을 자리도 없다. 넓은 방은 막을 곳이 너무 많다.
        assertNull(RefugeSearch.find(caveRoom(), FEET, threat, block -> false, BLOCKS));
    }

    @Test
    void needsEnoughBlocksToSealTheWayIn() {
        assertNull(RefugeSearch.find(caveRoom(), FEET, new BlockPoint(-5, 17, 0), block -> true, 0));
    }

    // 파고 들어가 막은 자리, 뒤를 막은 굴은 막힌 공간이다. 방처럼 넓은 곳이나 한 칸짜리 틈으로 방과 이어진 곳은 아니다.
    @Test
    void knowsWhenItIsInAClosedSpace() {
        Enclosure.Space pocket = Enclosure.around(new Rock().open(1, 17, 0, 1, 18, 0), new BlockPoint(1, 17, 0));
        assertTrue(pocket.closed());
        assertEquals(2, pocket.cells().size());
        Enclosure.Space tunnel = Enclosure.around(new Rock().open(-8, 17, 0, 0, 18, 0), FEET);
        assertTrue(tunnel.closed());
        assertEquals(18, tunnel.cells().size());
        assertFalse(Enclosure.around(caveRoom(), FEET).closed());

        // 회귀: 숨은 자리의 천장을 캤더니 방의 맨 윗줄과 한 칸짜리 틈으로 이어졌고, 그 틈으로 좀비가 보고 때렸다.
        Rock window = caveRoom().open(1, 17, 0, 1, 19, 0).set(FEET, BlockClass.SOLID).set(FEET.offset(0, 1, 0), BlockClass.SOLID);
        assertFalse(Enclosure.around(window, new BlockPoint(1, 17, 0)).closed());
        // 확인할 수 없는 청크에 닿아 있으면 막혔다고 믿지 않는다.
        Rock edge = new Rock().open(1, 17, 0, 1, 18, 0).set(new BlockPoint(2, 17, 0), BlockClass.UNLOADED);
        assertFalse(Enclosure.around(edge, new BlockPoint(1, 17, 0)).closed());
    }

    // 회귀: 숨은 자리에서 한 칸 파면 좀비가 보여서 다시 막고, 다시 파기를 끝없이 되풀이했다.
    // 몬스터가 있는 빈 곳으로 새로 뚫리는 블록을 미리 가려낸다.
    @Test
    void findsTheBlocksThatWouldOpenIntoAnotherSpace() {
        // 방의 동쪽 벽을 파고 들어가 들어온 쪽을 막은 상태
        Rock hidden = caveRoom().open(1, 17, 0, 1, 18, 0).set(FEET, BlockClass.SOLID).set(FEET.offset(0, 1, 0), BlockClass.SOLID);
        Set<BlockPoint> inside = Enclosure.around(hidden, new BlockPoint(1, 17, 0)).cells();
        // 머리 위 천장을 캐면 방의 맨 윗줄 (0,19,0) 과 이어진다.
        assertEquals(List.of(new BlockPoint(0, 19, 0)), Enclosure.openings(hidden, inside, List.of(new BlockPoint(1, 19, 0))));
        // 방 반대쪽으로 파는 굴은 어디로도 뚫리지 않는다.
        assertTrue(Enclosure.openings(hidden, inside, List.of(new BlockPoint(2, 18, 0), new BlockPoint(2, 17, 0))).isEmpty());
    }

    // 회귀: 숨었다가 굴을 파서 밖으로 나온 뒤(막힌 공간이 아님) 다시 굴로 들어와서, 좀비가 있는 방 쪽 벽을 뚫었다.
    // 내 공간이 밖과 이어져 있어도, 지금 이어져 있지 않은 방으로 뚫리는 것은 가려낸다.
    @Test
    void stillSeesANewOpeningWhenItsOwnSpaceIsNotClosed() {
        // 숨은 자리에서 동쪽으로 긴 굴을 파서 넓은 곳(x 12 이후)과 이어진 상태
        Rock dugOut = caveRoom().open(1, 17, 0, 12, 18, 0).open(12, 17, -4, 20, 22, 4)
                .set(FEET, BlockClass.SOLID).set(FEET.offset(0, 1, 0), BlockClass.SOLID);
        Enclosure.Space space = Enclosure.around(dugOut, new BlockPoint(1, 17, 0));
        assertFalse(space.closed());
        assertEquals(List.of(new BlockPoint(0, 19, 0)), Enclosure.openings(dugOut, space.cells(), List.of(new BlockPoint(1, 19, 0))));
        // 이미 이어져 있는 내 굴 쪽은 새로 뚫리는 곳이 아니다.
        assertTrue(Enclosure.openings(dugOut, space.cells(), List.of(new BlockPoint(2, 19, 0))).isEmpty());
    }
}
