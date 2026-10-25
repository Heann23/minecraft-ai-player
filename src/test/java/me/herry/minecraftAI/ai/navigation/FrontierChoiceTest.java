package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrontierChoiceTest {
    private static FrontierChoice.Candidate candidate(int x, boolean visited, int neighbors, int recent,
                                                       boolean blocked, int failures) {
        return new FrontierChoice.Candidate(new BlockPoint(x, 64, 0), visited, neighbors, recent, blocked, failures, Math.abs(x), 0);
    }

    @Test void unvisitedChunkWinsOverAnotherWalkInsideTheKnownArea() {
        var known = candidate(10, true, 2, 0, false, 0);
        var frontier = candidate(30, false, 3, 0, false, 0);
        assertEquals(frontier, FrontierChoice.select(List.of(known, frontier)).orElseThrow());
    }

    @Test void aFailedEndpointIsSkippedEvenWhenItsChunkIsUnknown() {
        var blocked = candidate(24, false, 8, 0, true, 0);
        var open = candidate(12, true, 0, 0, false, 0);
        assertEquals(open, FrontierChoice.select(List.of(blocked, open)).orElseThrow());
        assertTrue(FrontierChoice.select(List.of(blocked)).isEmpty());
    }

    @Test void unknownNeighborhoodAndRecentPathAffectKnownEndpoints() {
        var repeated = candidate(20, true, 4, 8, false, 0);
        var outward = candidate(-20, true, 4, 0, false, 0);
        assertEquals(outward, FrontierChoice.select(List.of(repeated, outward)).orElseThrow());
        assertEquals(candidate(20, true, 8, 0, false, 0), FrontierChoice.select(List.of(
                candidate(-20, true, 0, 0, false, 0), candidate(20, true, 8, 0, false, 0))).orElseThrow());
    }

    @Test void nearbyAccessFailuresReduceARepeatedApproachWithoutDeclaringTheWorldImpossible() {
        var narrow = candidate(24, false, 4, 0, false, 3);
        var alternative = candidate(-24, false, 4, 0, false, 0);
        assertEquals(alternative, FrontierChoice.select(List.of(narrow, alternative)).orElseThrow());
    }

    @Test void fourBlocksOfMovementAloneAreNotNewExploration() {
        var frontier = candidate(30, false, 3, 0, false, 0);
        assertFalse(FrontierChoice.reachedNewFrontier(frontier, new BlockPoint(0, 64, 0), new BlockPoint(4, 64, 0)));
        assertTrue(FrontierChoice.reachedNewFrontier(frontier, new BlockPoint(0, 64, 0), new BlockPoint(20, 64, 0)));
        assertFalse(FrontierChoice.reachedNewFrontier(candidate(30, true, 3, 0, false, 0),
                new BlockPoint(0, 64, 0), new BlockPoint(20, 64, 0)));
        assertFalse(FrontierChoice.reachedNewFrontier(frontier, new BlockPoint(15, 64, 0), new BlockPoint(16, 64, 0)));
    }
}
