package me.herry.minecraftAI.ai.world;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldModelTest {
    private static final UUID OVERWORLD = UUID.randomUUID();
    private static final UUID NETHER = UUID.randomUUID();

    private final WorldModel model = new WorldModel();

    @Test
    void firstWorkbenchBecomesProvisionalHome() {
        assertNull(model.getHome());
        Base home = model.ensureHome(OVERWORLD, new BlockPoint(10, 64, 10));
        // 이미 거점이 있으면 다른 자리를 넘겨도 바뀌지 않는다.
        assertEquals(home, model.ensureHome(OVERWORLD, new BlockPoint(99, 64, 99)));
        assertEquals(new BlockPoint(10, 64, 10), home.center());
        assertFalse(home.isSheltered());
    }

    @Test
    void homeIsOnlyUsableInItsOwnWorld() {
        model.ensureHome(OVERWORLD, new BlockPoint(0, 64, 0));
        assertNotNull(model.homeIn(OVERWORLD));
        // 네더에 있을 때는 오버월드의 집으로 걸어갈 수 없다.
        assertNull(model.homeIn(NETHER));
    }

    @Test
    void baseKnowsItsBuildingAndSafeRange() {
        Base home = new Base(OVERWORLD, new BlockPoint(0, 64, 0));
        assertFalse(home.isInsideBuilding(OVERWORLD, new BlockPoint(0, 64, 0)));

        home.setBounds(new BlockPoint(-2, 63, -2), new BlockPoint(2, 66, 2));
        assertTrue(home.isInsideBuilding(OVERWORLD, new BlockPoint(1, 64, -1)));
        assertFalse(home.isInsideBuilding(OVERWORLD, new BlockPoint(3, 64, 0)));
        assertFalse(home.isInsideBuilding(NETHER, new BlockPoint(1, 64, -1)));

        // 안전 범위는 높이를 보지 않는다. 집 바로 밑의 광산도 거점 근처다.
        assertTrue(home.isInSafeRange(OVERWORLD, new BlockPoint(10, 12, 10)));
        assertFalse(home.isInSafeRange(OVERWORLD, new BlockPoint(40, 64, 0)));
    }

    @Test
    void portalBlocksOfTheSamePortalAreRememberedOnce() {
        model.rememberPortal(WorldModel.PortalKind.NETHER, OVERWORLD, new BlockPoint(100, 64, 100));
        model.rememberPortal(WorldModel.PortalKind.NETHER, OVERWORLD, new BlockPoint(101, 65, 100));
        model.rememberPortal(WorldModel.PortalKind.NETHER, NETHER, new BlockPoint(12, 70, 12));

        assertEquals(2, model.getPortals().size());
        assertTrue(model.hasNetherPortal());
        // 네더에서 돌아올 포탈은 네더에 있는 것을 찾아야 한다.
        WorldModel.Portal back = model.nearestPortal(WorldModel.PortalKind.NETHER, NETHER, new BlockPoint(0, 70, 0));
        assertNotNull(back);
        assertEquals(new BlockPoint(12, 70, 12), back.pos());
        assertNull(model.nearestPortal(WorldModel.PortalKind.END, OVERWORLD, new BlockPoint(0, 64, 0)));
    }

    @Test
    void picksTheLeastExploredDirection() {
        // 동쪽(+X)만 빼고 주변을 모두 가 본 것으로 표시한다.
        for (int x = -6; x <= 1; x++) {
            for (int z = -6; z <= 6; z++) model.markExplored(OVERWORLD, x, z);
        }
        assertTrue(model.isExplored(OVERWORLD, 0, 0));
        assertFalse(model.isExplored(OVERWORLD, 3, 0));
        assertFalse(model.isExplored(NETHER, 0, 0));

        assertArrayEquals(new int[]{1, 0}, model.leastExploredDirection(OVERWORLD, 0, 0));
    }

    @Test
    void exploredChunksAreBounded() {
        for (int x = 0; x < 5000; x++) model.markExplored(OVERWORLD, x, 0);

        assertEquals(4096, model.exploredCount(OVERWORLD));
        // 오래된 것부터 잊는다.
        assertFalse(model.isExplored(OVERWORLD, 0, 0));
        assertTrue(model.isExplored(OVERWORLD, 4999, 0));
    }

    @Test
    void remembersWhatIsInEachChest() {
        BlockPoint chest = new BlockPoint(-1, 64, 0);
        model.rememberContents(OVERWORLD, chest, Map.of("IRON_INGOT", 12, "COBBLESTONE", 64));
        model.rememberContents(OVERWORLD, new BlockPoint(5, 64, 0), Map.of("IRON_INGOT", 3));

        assertEquals(15, model.storedCount("IRON_INGOT"));
        assertEquals(0, model.storedCount("DIAMOND"));
        WorldModel.Place best = model.chestHolding("IRON_INGOT");
        assertNotNull(best);
        assertEquals(chest, best.pos());
        assertNull(model.chestHolding("DIAMOND"));

        // 다시 열어 보면 새 내용으로 덮어쓴다.
        model.rememberContents(OVERWORLD, chest, Map.of("COBBLESTONE", 10));
        assertEquals(3, model.storedCount("IRON_INGOT"));
    }

    @Test
    void deathsAreBounded() {
        for (int i = 0; i < 20; i++) model.recordDeath(OVERWORLD, new BlockPoint(i, 64, 0));

        assertEquals(8, model.getDeaths().size());
        assertEquals(new BlockPoint(19, 64, 0), model.lastDeath().pos());
    }

    // 서버를 재시작해도 큰 그림(거점, 포탈, 상자, 탐험한 곳, 진행)이 그대로 돌아와야 한다.
    @Test
    void survivesSaveAndRestore() {
        Base home = model.ensureHome(OVERWORLD, new BlockPoint(10, 64, -20));
        home.setSheltered(true);
        home.setBounds(new BlockPoint(8, 63, -22), new BlockPoint(12, 66, -18));
        home.setWorkbench(new BlockPoint(9, 64, -21));
        home.setBed(new BlockPoint(11, 64, -20));
        home.setEntrance(new BlockPoint(10, 64, -18));
        home.addChest(new BlockPoint(9, 64, -20));
        model.rememberPortal(WorldModel.PortalKind.NETHER, OVERWORLD, new BlockPoint(30, 64, 30));
        model.setStronghold(new WorldModel.Place(OVERWORLD, new BlockPoint(1200, 30, -800)));
        model.setEndPortalReady(true);
        model.recordDeath(NETHER, new BlockPoint(5, 40, 5));
        model.markExplored(OVERWORLD, 3, -7);
        model.markExplored(OVERWORLD, -1, 2);
        model.rememberContents(OVERWORLD, new BlockPoint(9, 64, -20), Map.of("IRON_INGOT", 12, "DIAMOND", 2));

        WorldModel restored = new WorldModel();
        restored.importState(model.exportState());

        Base restoredHome = restored.getHome();
        assertNotNull(restoredHome);
        assertEquals(new BlockPoint(10, 64, -20), restoredHome.center());
        assertTrue(restoredHome.isSheltered());
        assertTrue(restoredHome.isInsideBuilding(OVERWORLD, new BlockPoint(10, 64, -20)));
        assertEquals(new BlockPoint(9, 64, -21), restoredHome.workbench());
        assertEquals(new BlockPoint(11, 64, -20), restoredHome.bed());
        assertEquals(new BlockPoint(10, 64, -18), restoredHome.entrance());
        assertNull(restoredHome.furnace());
        assertEquals(1, restoredHome.chests().size());

        assertTrue(restored.hasNetherPortal());
        assertEquals(new BlockPoint(1200, 30, -800), restored.getStronghold().pos());
        assertTrue(restored.isEndPortalReady());
        assertFalse(restored.isDragonDefeated());
        assertEquals(1, restored.getDeaths().size());
        assertEquals(NETHER, restored.lastDeath().world());
        assertTrue(restored.isExplored(OVERWORLD, 3, -7));
        assertTrue(restored.isExplored(OVERWORLD, -1, 2));
        assertEquals(2, restored.exploredCount(OVERWORLD));
        assertEquals(12, restored.storedCount("IRON_INGOT"));
        assertEquals(2, restored.storedCount("DIAMOND"));
    }

    // 저장 파일의 일부가 망가져도 읽을 수 있는 것은 읽는다.
    @Test
    void tolerantOfBrokenSavedData() {
        WorldModel restored = new WorldModel();
        restored.importState(Map.of(
                "portals", java.util.List.of("NETHER|" + OVERWORLD + "|1,2,3", "garbage", "UNKNOWN_KIND|" + OVERWORLD + "|1,2,3"),
                "stronghold", "not a place",
                "deaths", java.util.List.of(OVERWORLD + "|x,y,z"),
                "home", Map.of("world", "not-a-uuid", "center", "0,0,0")));

        assertEquals(1, restored.getPortals().size());
        assertNull(restored.getStronghold());
        assertTrue(restored.getDeaths().isEmpty());
        assertNull(restored.getHome());
    }
}
