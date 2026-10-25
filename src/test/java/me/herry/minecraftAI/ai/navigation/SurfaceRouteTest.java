package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceRouteTest {
    private static final int Y = 62;
    private static SurfaceRoute finish(SurfaceRoute search) {
        int calls = 0;
        while (search.status() == SurfaceRoute.Status.SEARCHING && calls++ < 1000) search.advance(16, () -> false);
        assertNotEquals(SurfaceRoute.Status.SEARCHING, search.status());
        return search;
    }

    @Test void followsObservedWaterAroundABlockedColumnWithoutCuttingCorners() {
        Set<BlockPoint> blocked = Set.of(new BlockPoint(1, Y, 0));
        var search = finish(new SurfaceRoute(new BlockPoint(0, Y, 0), new BlockPoint(3, Y, 0),
                (x, z) -> Math.abs(x) <= 5 && Math.abs(z) <= 2
                        ? blocked.contains(new BlockPoint(x, Y, z)) ? SurfaceRoute.Kind.BLOCKED : SurfaceRoute.Kind.WATER
                        : SurfaceRoute.Kind.UNKNOWN));
        assertEquals(SurfaceRoute.Status.FOUND, search.status());
        assertFalse(search.path().contains(new BlockPoint(1, Y, 0)));
        for (int i = 1; i < search.path().size(); i++) {
            assertEquals(1, search.path().get(i).distance(search.path().get(i - 1)));
        }
        assertEquals(new BlockPoint(3, Y, 0), search.path().getLast());
    }

    @Test void unknownWaterIsNotAnAllowedRouteOrProofThatTheWorldHasNoRoute() {
        var search = finish(new SurfaceRoute(new BlockPoint(0, Y, 0), new BlockPoint(2, Y, 0),
                (x, z) -> z == 0 && x >= 0 && x <= 2
                        ? x == 1 ? SurfaceRoute.Kind.UNKNOWN : SurfaceRoute.Kind.WATER : SurfaceRoute.Kind.BLOCKED));
        assertEquals(SurfaceRoute.Status.NO_ROUTE, search.status());
        assertTrue(search.encounteredUnknown());
        assertTrue(search.path().isEmpty());
    }

    @Test void surfaceRouteCanEnterWaterAndFinishOnShoreButDoesNotWalkAcrossIntermediateLand() {
        var good = finish(new SurfaceRoute(new BlockPoint(0, Y, 0), new BlockPoint(3, Y, 0),
                (x, z) -> z != 0 || x < 0 || x > 3 ? SurfaceRoute.Kind.BLOCKED
                        : x == 0 || x == 3 ? SurfaceRoute.Kind.SHORE : SurfaceRoute.Kind.WATER));
        assertEquals(SurfaceRoute.Status.FOUND, good.status());
        var bad = finish(new SurfaceRoute(new BlockPoint(0, Y, 0), new BlockPoint(3, Y, 0),
                (x, z) -> z != 0 || x < 0 || x > 3 ? SurfaceRoute.Kind.BLOCKED
                        : x == 1 ? SurfaceRoute.Kind.SHORE : SurfaceRoute.Kind.WATER));
        assertEquals(SurfaceRoute.Status.NO_ROUTE, bad.status());
    }

    @Test void budgetPauseKeepsItsUnfinishedOutgoingEdges() {
        var search = new SurfaceRoute(new BlockPoint(0, Y, 0), new BlockPoint(12, Y, 0),
                (x, z) -> z == 0 && x >= 0 && x <= 12 ? SurfaceRoute.Kind.WATER : SurfaceRoute.Kind.BLOCKED);
        AtomicInteger reads = new AtomicInteger();
        for (int calls = 0; search.status() == SurfaceRoute.Status.SEARCHING && calls < 200; calls++) {
            reads.set(0);
            search.advance(1, () -> reads.incrementAndGet() > 4);
        }
        assertEquals(SurfaceRoute.Status.FOUND, search.status());
        assertEquals(13, search.path().size());
        assertThrows(UnsupportedOperationException.class, () -> search.path().clear());
    }

    @Test void exhaustedOrZeroBudgetDoesNotReadTheWorld() {
        AtomicInteger reads = new AtomicInteger();
        var search = new SurfaceRoute(new BlockPoint(0, Y, 0), new BlockPoint(2, Y, 0), (x, z) -> {
            reads.incrementAndGet(); return SurfaceRoute.Kind.WATER;
        });
        assertEquals(SurfaceRoute.Status.SEARCHING, search.advance(0, () -> false));
        assertEquals(SurfaceRoute.Status.SEARCHING, search.advance(2, () -> true));
        assertEquals(0, reads.get());
    }

    @Test void aSearchLimitIsDistinctFromAConfirmedLackOfSupportedRoute() {
        var target = new BlockPoint(40, Y, 0);
        var search = finish(new SurfaceRoute(new BlockPoint(0, Y, 0), target,
                (x, z) -> Math.abs(x - target.x()) + Math.abs(z - target.z()) == 1
                        ? SurfaceRoute.Kind.BLOCKED : SurfaceRoute.Kind.WATER));
        assertEquals(SurfaceRoute.Status.LIMIT_REACHED, search.status());
        assertTrue(search.path().isEmpty());
    }

    @Test void destinationArrivalRequiresItsActualCoordinatesAndBreathingSurfaceOrGround() {
        var target = new BlockPoint(12, Y, 0);
        assertTrue(SurfaceRoute.arrived(12.5, 62.3, 0.5, target, true, false, false, 300, 300));
        assertTrue(SurfaceRoute.arrived(12.5, 63.0, 0.5, target, false, true, false, 300, 300));
        assertFalse(SurfaceRoute.arrived(4.5, 62.3, 0.5, target, true, false, false, 300, 300));
        assertFalse(SurfaceRoute.arrived(12.5, 62.3, 0.5, target, true, false, true, 300, 300));
        assertFalse(SurfaceRoute.arrived(12.5, 62.3, 0.5, target, true, false, false, 60, 300));
        assertFalse(SurfaceRoute.arrived(12.5, 63.0, 0.5, target, false, false, false, 300, 300));
    }

    @Test void unsupportedVerticalOrLongRangeLegIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SurfaceRoute(new BlockPoint(0, Y, 0),
                new BlockPoint(49, Y, 0), (x, z) -> SurfaceRoute.Kind.WATER));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceRoute(new BlockPoint(0, Y, 0),
                new BlockPoint(2, Y + 1, 0), (x, z) -> SurfaceRoute.Kind.WATER));
    }
}
