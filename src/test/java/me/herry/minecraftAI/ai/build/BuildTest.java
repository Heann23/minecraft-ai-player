package me.herry.minecraftAI.ai.build;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final double EYE_HEIGHT = 1.62;
    // 서바이벌에서 블록에 손이 닿는 거리
    private static final double REACH = 4.5;

    private final Blueprint shelter = Blueprints.shelter();

    @Test
    void shelterHasWallsRoofDoorAndFixtures() {
        // 5x5 둘레 16칸 x 2층에서 문 자리 2칸을 뺀다.
        assertEquals(30, shelter.count(BlockRole.WALL));
        assertEquals(25, shelter.count(BlockRole.ROOF));
        assertEquals(25, shelter.count(BlockRole.FLOOR));
        assertEquals(1, shelter.count(BlockRole.DOOR));
        assertEquals(1, shelter.count(BlockRole.WORKBENCH));
        assertEquals(1, shelter.count(BlockRole.CHEST));
        assertEquals(1, shelter.count(BlockRole.FURNACE));
        assertEquals(1, shelter.count(BlockRole.BED));
        assertEquals(1, shelter.count(BlockRole.TORCH));
        assertEquals(new BlockPoint(-2, -1, -2), shelter.min());
        assertEquals(new BlockPoint(2, 2, 2), shelter.max());
    }

    // 기준점 한자리에 서서 집 전체를 지을 수 있어야 한다.
    @Test
    void everyPartIsWithinReachOfTheOrigin() {
        for (Blueprint.Part part : shelter.parts()) {
            double dx = part.dx();
            double dy = part.dy() + 0.5 - EYE_HEIGHT;
            double dz = part.dz();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertTrue(distance <= REACH, part + " is " + distance + " away");
        }
    }

    // 블록은 이웃 블록에 붙여야 놓을 수 있다. 짓는 순서대로 놓았을 때 모든 벽과 지붕에 붙일 곳이 있는지 확인한다.
    @Test
    void buildOrderAlwaysHasSomethingToAttachTo() {
        Set<BlockPoint> solid = new HashSet<>();
        // 평평한 땅 위에 짓는다고 가정한다.
        for (Blueprint.Part part : shelter.partsOf(BlockRole.FLOOR)) solid.add(new BlockPoint(part.dx(), part.dy(), part.dz()));

        int[][] neighbors = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (Blueprint.Part part : shelter.parts()) {
            if (part.role() != BlockRole.WALL && part.role() != BlockRole.ROOF) continue;
            BlockPoint cell = new BlockPoint(part.dx(), part.dy(), part.dz());
            boolean supported = false;
            for (int[] offset : neighbors) {
                if (solid.contains(cell.offset(offset[0], offset[1], offset[2]))) supported = true;
            }
            assertTrue(supported, "nothing to attach " + part + " to");
            solid.add(cell);
        }
    }

    @Test
    void interiorIsWalkableAndFixturesDoNotBlockTheWay() {
        Set<BlockPoint> occupied = new HashSet<>();
        for (Blueprint.Part part : shelter.parts()) {
            if (part.role() == BlockRole.CLEAR || part.role() == BlockRole.FLOOR || part.role() == BlockRole.TORCH) continue;
            occupied.add(new BlockPoint(part.dx(), part.dy(), part.dz()));
        }
        // 침대의 머리 칸은 발치에서 남쪽으로 한 칸이다.
        Blueprint.Part bed = shelter.partsOf(BlockRole.BED).getFirst();
        occupied.add(new BlockPoint(bed.dx(), bed.dy(), bed.dz() + 1));

        Blueprint.Part door = shelter.partsOf(BlockRole.DOOR).getFirst();
        // 서는 자리와, 거기서 문까지 가는 길은 비어 있어야 한다.
        assertFalse(occupied.contains(new BlockPoint(0, 0, 0)));
        assertFalse(occupied.contains(new BlockPoint(0, 1, 0)));
        assertFalse(occupied.contains(new BlockPoint(0, 0, 1)));
        assertEquals(new BlockPoint(0, 0, 2), new BlockPoint(door.dx(), door.dy(), door.dz()));
        // 문 위 칸에는 벽을 놓지 않는다 (문이 두 칸을 차지한다).
        assertFalse(occupied.contains(new BlockPoint(0, 1, 2)));
        // 시설끼리 겹치지 않는다.
        int fixtures = shelter.count(BlockRole.WORKBENCH) + shelter.count(BlockRole.CHEST) + shelter.count(BlockRole.FURNACE) + 2;
        Set<BlockPoint> fixtureCells = new HashSet<>();
        for (Blueprint.Part part : shelter.parts()) {
            BlockRole role = part.role();
            if (role == BlockRole.WORKBENCH || role == BlockRole.CHEST || role == BlockRole.FURNACE || role == BlockRole.BED) {
                fixtureCells.add(new BlockPoint(part.dx(), part.dy(), part.dz()));
            }
        }
        fixtureCells.add(new BlockPoint(bed.dx(), bed.dy(), bed.dz() + 1));
        assertEquals(fixtures, fixtureCells.size());
    }

    @Test
    void jobReportsWhatIsLeftToBuild() {
        BlockPoint origin = new BlockPoint(100, 64, -50);
        BuildJob job = new BuildJob(WORLD, origin, shelter);
        assertEquals(new BlockPoint(98, 63, -52), job.min());
        assertEquals(new BlockPoint(102, 66, -48), job.max());

        // 평평한 빈 땅: 비울 칸과 바닥은 이미 되어 있고 나머지는 전부 남았다.
        Set<Blueprint.Part> built = new HashSet<>();
        for (Blueprint.Part part : shelter.parts()) {
            if (part.role() == BlockRole.CLEAR || part.role() == BlockRole.FLOOR) built.add(part);
        }
        assertEquals(55, job.pendingStructural(built::contains));
        assertFalse(job.isComplete(built::contains));
        // 남은 일의 첫 순서는 벽이다.
        assertEquals(BlockRole.WALL, job.pending(built::contains).getFirst().role());

        // 벽과 지붕, 문, 작업대, 상자만 있으면 완성이다. 화로, 횃불, 침대는 없어도 된다.
        for (Blueprint.Part part : shelter.parts()) {
            if (part.role().isRequired()) built.add(part);
        }
        assertEquals(0, job.pendingStructural(built::contains));
        assertTrue(job.isComplete(built::contains));
        List<Blueprint.Part> optional = job.pending(built::contains);
        assertEquals(3, optional.size());
        for (Blueprint.Part part : optional) assertFalse(part.role().isRequired());
    }

    // 누가 벽을 부수면 다시 "지을 것"으로 잡혀서 고칠 수 있어야 한다.
    @Test
    void brokenWallShowsUpAsPendingAgain() {
        BuildJob job = new BuildJob(WORLD, new BlockPoint(0, 64, 0), shelter);
        Set<Blueprint.Part> built = new HashSet<>(shelter.parts());
        assertTrue(job.pending(built::contains).isEmpty());

        Blueprint.Part wall = shelter.partsOf(BlockRole.WALL).get(7);
        built.remove(wall);
        assertEquals(List.of(wall), job.pending(built::contains));
        assertFalse(job.isComplete(built::contains));
    }

    @Test
    void jobSurvivesSaveAndRestore() {
        BuildJob job = new BuildJob(WORLD, new BlockPoint(100, 64, -50), shelter);
        BuildJob restored = BuildJob.importState(job.exportState());

        assertNotNull(restored);
        assertEquals(WORLD, restored.world());
        assertEquals(new BlockPoint(100, 64, -50), restored.origin());
        assertEquals(Blueprints.SHELTER, restored.blueprint().name());

        // 없어진 설계도나 망가진 좌표는 읽지 않는다.
        assertNull(BuildJob.importState(Map.of("world", WORLD.toString(), "origin", "1,2,3", "blueprint", "castle")));
        assertNull(BuildJob.importState(Map.of("world", WORLD.toString(), "origin", "oops", "blueprint", Blueprints.SHELTER)));
        assertNull(Blueprints.byName("castle"));
    }
}
