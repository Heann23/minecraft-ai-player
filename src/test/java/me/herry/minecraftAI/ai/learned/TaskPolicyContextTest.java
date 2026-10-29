package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskPolicyContextTest {
    @Test void goalAblationPreservesOutcomeHistorySightAndActualCompletionAccounting() {
        UUID world = UUID.randomUUID(); var memory = new PolicyTaskMemory();
        memory.acquire(world, "OAK_LOG", 1, 10);
        memory.ended(world, 11, "mine:1", "SUCCESS", "");
        var actual = memory.observe(world, Map.of("OAK_LOG", 1));
        double[] state = new double[PolicyFrame.STATE_SIZE];
        Arrays.fill(state, .25);
        TaskPolicyContext.encode(state, actual, 12, world);
        double[] before = state.clone();
        TaskPolicyContext.maskObjective(state);
        for (int i = 0; i < state.length; i++)
            assertEquals(i >= 208 && i < 232 ? 0 : before[i], state[i]);
        assertTrue(actual.currentlyAchieved());
        assertEquals(1, actual.owned());
        assertEquals(1, state[233]);
        assertEquals(actual, memory.observe(world, Map.of("OAK_LOG", 1)));
    }
    @Test void goalProgressAndWorldScopeReachTheVectorWithoutChangingPriorFeatures() {
        UUID world = UUID.randomUUID(); var memory = new PolicyTaskMemory();
        memory.acquire(world, "OAK_LOG", 4, 10);
        double[] state = new double[PolicyFrame.STATE_SIZE]; Arrays.fill(state, 0, 208, .25);
        TaskPolicyContext.encode(state, memory.observe(world, Map.of("OAK_LOG", 2)), 20, world);
        assertEquals(1, state[208]); assertEquals(1, state[209]); assertEquals(2 / 64.0, state[212]);
        for (int i = 0; i < 208; i++) assertEquals(.25, state[i]);
        TaskPolicyContext.encode(state, memory.observe(UUID.randomUUID(), Map.of("OAK_LOG", 8)), 20, world);
        assertEquals(0, state[209]); assertEquals(0, state[213]);
        memory.clearObjective(); TaskPolicyContext.encode(state, memory.observe(world, Map.of()), 21, world);
        for (int i = 208; i < state.length; i++) assertEquals(0, state[i]);
    }
    @Test void anotherWorldsHistoryCannotBecomeCurrentWorldEvidenceWithoutAnObjective() {
        var memory = new PolicyTaskMemory(); UUID world = UUID.randomUUID();
        memory.ended(UUID.randomUUID(), 1, "mine:1", "FAILED", "blocked");
        double[] state = new double[PolicyFrame.STATE_SIZE];
        TaskPolicyContext.encode(state, memory.observe(world, Map.of()), 2, world);
        assertEquals(0, state[233]);
        memory.ended(world, 2, "move:1", "SUCCESS", "");
        TaskPolicyContext.encode(state, memory.observe(world, Map.of()), 3, world);
        assertEquals(1, state[233]);
    }
}
