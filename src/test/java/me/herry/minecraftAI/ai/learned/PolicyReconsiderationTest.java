package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolicyReconsiderationTest {
    @Test void longHoldsRemainExpressibleButCannotSuppressPolicyObservationsForThirtySeconds() {
        var base = new PolicyCandidates.Candidate("wait", new PrimitiveCommand.Wait(5), null, null,
                new PolicyFrame.Option("wait", new double[PolicyFrame.ACTION_SIZE]));
        for (int hold : new int[]{5, 20, 80, 600}) {
            var selected = base.committed(hold);
            assertEquals(hold, selected.commitmentTicks());
            assertEquals(hold / 600.0, selected.encoded().features()[30]);
            assertEquals(Math.min(hold, 20), selected.reconsiderAfterTicks());
        }
        assertEquals(1, base.reconsiderAfterTicks());
    }
}
