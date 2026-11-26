package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionPolicyContextTest {
    @Test void observedProgressIsDistinctFromUnknownAndCancellationFromFailure() {
        var world = UUID.randomUUID();
        var memory = new PolicyTaskMemory.View("policy-task-memory/v1", null, false, 0, 0, false,
                List.of(new PolicyTaskMemory.Outcome(world, 1, "", "mine:a", "FAILED", "cancelled")));
        double[] state = new double[PolicyFrame.STATE_SIZE];
        state[399] = 0.75;
        ExecutionPolicyContext.encode(state, new ExecutionPolicyContext.Execution(true, true, true, 0.42, 25, 2, "mine:a"), memory, world);
        assertEquals(0.42, state[403]); assertEquals(1, state[402]); assertEquals(1, state[418]);
        assertEquals(0, state[419]); assertEquals(0.75, state[399]);
        ExecutionPolicyContext.encode(state, new ExecutionPolicyContext.Execution(false, false, false, 0, 0, 0, ""), memory, UUID.randomUUID());
        assertEquals(0, state[402]); assertEquals(0, state[416]);
    }
}
