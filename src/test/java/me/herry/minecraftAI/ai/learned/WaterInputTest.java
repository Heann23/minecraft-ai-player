package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Input contracts only, not a Paper swimming/rescue acceptance test. */
class WaterInputTest {
    @Test void allIndependentKeyCombinationsAndVerticalHeadingsRemainAvailable() {
        UUID world = UUID.randomUUID();
        var choices = WaterInput.choices(world, 0, 0, 144);
        assertEquals(144, new HashSet<>(choices).size());
        for (int forward : new int[]{0, 1}) for (int vertical : new int[]{-1, 0, 1}) for (boolean sprint : new boolean[]{false, true}) {
            assertTrue(choices.stream().anyMatch(input -> input.forward() == forward && input.vertical() == vertical && input.sprint() == sprint));
        }
        assertTrue(choices.stream().anyMatch(input -> input.heading().y() > 0));
        assertTrue(choices.stream().anyMatch(input -> input.heading().y() < 0));
        for (var input : choices) {
            var h = input.heading();
            assertEquals(1, h.x()*h.x() + h.y()*h.y() + h.z()*h.z(), 1e-12);
            assertEquals(world, input.world());
        }
        Set<WaterInput> first = new HashSet<>();
        for (int cursor = 0; cursor < 144; cursor++) first.add(WaterInput.choices(world, 0, cursor, 1).getFirst());
        assertEquals(new HashSet<>(choices), first);
    }
    @Test void hungerRestrictsSprintRatherThanChoosingAscentOrAbortingWaterInput() {
        var input = new WaterInput(UUID.randomUUID(), 0, 60, 1, -1, true);
        assertFalse(input.canSprint(6, false, false, true));
        assertTrue(input.canSprint(7, false, false, true));
        assertFalse(input.canSprint(20, true, false, true));
        assertFalse(input.canSprint(20, false, true, false));
        assertTrue(new WaterInput(input.world(), 0, 0, 1, 1, true).canSprint(20, false, false, false));
        assertEquals(-1, input.vertical());
        assertEquals(60, input.pitch());
    }
    @Test void commitmentAndFamilySelectionRetainTheChosenKeysWithoutALegacySwimCommand() {
        var input = new WaterInput(UUID.randomUUID(), 0, -60, 1, 1, false);
        var base = new PolicyCandidates.Candidate(input.identity(), null, null, null,
                new PolicyFrame.Option(input.identity(), new double[PolicyFrame.ACTION_SIZE]), 0, input.identity(), null, input);
        var candidate = base.committed(80);
        assertNotEquals(candidate.inputId(), base.committed(5).inputId());
        assertEquals(input, candidate.water());
        assertEquals(PrimitiveType.SWIM, candidate.type());
        assertEquals(List.of(candidate), PolicyCandidates.selectFair(List.of(candidate), ""));
        assertEquals(80, candidate.commitmentTicks());
    }
}
