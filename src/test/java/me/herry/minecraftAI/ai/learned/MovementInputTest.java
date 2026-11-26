package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Key contracts only, not actual travel, swimming or survival acceptance. */
class MovementInputTest {
    @Test void independentKeysAndHeadingsAreAvailableWithoutAWaterDomainArgument() {
        UUID world = UUID.randomUUID();
        var choices = MovementInput.choices(world, 0, 0, MovementInput.CHOICES);
        assertEquals(864, new HashSet<>(choices).size());
        for (var input : choices) {
            var h = input.heading();
            assertEquals(1, h.x()*h.x() + h.y()*h.y() + h.z()*h.z(), 1e-12);
            assertEquals(world, input.world());
        }
        var prefix = MovementInput.choices(world, 0, 0, 24);
        for (int key : new int[]{-1, 0, 1}) {
            assertTrue(prefix.stream().anyMatch(input -> input.forward() == key));
            assertTrue(prefix.stream().anyMatch(input -> input.strafe() == key));
        }
        assertTrue(prefix.stream().anyMatch(MovementInput::jump));
        assertTrue(prefix.stream().anyMatch(MovementInput::sneak));
        assertTrue(choices.stream().anyMatch(input -> input.jump() && input.sneak()));
        Set<MovementInput> first = new HashSet<>();
        for (int cursor = 0; cursor < MovementInput.CHOICES; cursor++) first.add(MovementInput.choices(world, 0, cursor, 1).getFirst());
        assertEquals(new HashSet<>(choices), first);
    }
    @Test void normalSprintPermissionsDoNotChooseOrModifyOtherRequestedKeys() {
        var input = new MovementInput(UUID.randomUUID(), 0, 60, 1, -1, false, true, true);
        assertFalse(input.canSprint(6, false, false, true));
        assertTrue(input.canSprint(7, false, false, true));
        assertFalse(input.canSprint(20, true, false, true));
        assertFalse(input.canSprint(20, false, true, false));
        assertTrue(input.sneak()); assertEquals(-1, input.strafe());
        assertFalse(new MovementInput(input.world(), 0, 0, -1, 0, false, false, true).canSprint(20, false, false, false));
    }
    @Test void keyDurationAndCategorySurviveCommitmentWithoutChangingOldPrimitiveOrdinals() {
        var input = new MovementInput(UUID.randomUUID(), 0, -60, 1, 1, true, true, false);
        var base = new PolicyCandidates.Candidate(input.identity(), null, null, null,
                new PolicyFrame.Option(input.identity(), new double[PolicyFrame.ACTION_SIZE]), 0, input.identity(), null, input);
        var selected = base.committed(80);
        assertNotEquals(selected.inputId(), base.committed(5).inputId());
        assertEquals(input, selected.movement()); assertEquals(PrimitiveType.MOVE_INPUT, selected.type());
        assertEquals(List.of(selected), PolicyCandidates.selectFair(List.of(selected), ""));
        assertEquals(16, PrimitiveType.SWIM.ordinal());
        assertFalse(me.herry.minecraftAI.ai.primitive.runtime.PrimitiveAdapter.capabilities().stream()
                .filter(capability -> capability.type() == PrimitiveType.MOVE_INPUT).findFirst().orElseThrow().supported());
        for (var type : PrimitiveType.values()) assertTrue(type.ordinal() < 24, "type encoding collides with geometry features");
    }
}
