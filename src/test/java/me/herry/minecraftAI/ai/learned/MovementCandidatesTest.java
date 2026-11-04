package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Candidate coverage only; not Minecraft path execution or survival acceptance. */
class MovementCandidatesTest {
    @Test void narrowAdjacentLandingsAndExistingTwoBlockLandingsRemainAvailable() {
        var offsets = PolicyCandidates.movementOffsets(0);
        assertEquals(48, offsets.size());
        assertEquals(48, new HashSet<>(offsets).size());
        for (int height = -1; height <= 1; height++) {
            assertTrue(offsets.contains(new BlockPoint(1, height, 0)));
            assertTrue(offsets.contains(new BlockPoint(2, height, 0)));
            assertTrue(offsets.contains(new BlockPoint(-1, height, -1)));
            assertTrue(offsets.contains(new BlockPoint(-2, height, -2)));
        }
        assertTrue(offsets.stream().noneMatch(point -> point.x() == 0 && point.z() == 0));
    }

    @Test void evenAOneCandidatePrefixCannotPermanentlyHideAnyLanding() {
        Set<BlockPoint> firstChoices = new HashSet<>();
        for (int capture = 0; capture < 48; capture++) {
            var offered = PolicyCandidates.movementOffsets(capture);
            firstChoices.add(offered.getFirst());
            assertEquals(new HashSet<>(PolicyCandidates.movementOffsets(0)), new HashSet<>(offered));
        }
        assertEquals(48, firstChoices.size());
        assertEquals(PolicyCandidates.movementOffsets(0), PolicyCandidates.movementOffsets(48));
        assertEquals(PolicyCandidates.movementOffsets(47), PolicyCandidates.movementOffsets(-1));
    }
}
