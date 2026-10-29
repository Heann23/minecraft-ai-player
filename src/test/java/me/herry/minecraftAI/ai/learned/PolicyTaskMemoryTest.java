package me.herry.minecraftAI.ai.learned;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyTaskMemoryTest {
    @Test void goalAndOutcomeIdentitySurviveRestoreAndAnotherWorldDoesNotCompleteTheTask() {
        UUID world = UUID.randomUUID();
        var memory = new PolicyTaskMemory();
        memory.acquire(world, "OAK_LOG", 4, 10);
        memory.ended(world, 11, "mine:1", "FAILED", "unreachable");
        var before = memory.observe(world, Map.of("OAK_LOG", 2, "OAK_PLANKS", 64));
        assertEquals(2, before.remaining());
        assertFalse(before.currentlyAchieved());
        var restored = new PolicyTaskMemory(); restored.importState(memory.exportState());
        assertEquals(before, restored.observe(world, Map.of("OAK_LOG", 2, "OAK_PLANKS", 64)));
        assertFalse(restored.observe(UUID.randomUUID(), Map.of("OAK_LOG", 64)).currentlyAchieved());
        assertTrue(restored.observe(world, Map.of("OAK_LOG", 4)).currentlyAchieved());
    }
    @Test void historyIsBoundedAndInvalidRestoreCannotPartiallyReplaceAnExistingGoal() {
        UUID world = UUID.randomUUID(); var memory = new PolicyTaskMemory();
        memory.acquire(world, "COOKED_BEEF", 2, 0);
        for (int i = 0; i < 12; i++) memory.ended(world, i, "action:" + i, "SUCCESS", "");
        var before = memory.observe(world, Map.of());
        assertEquals(8, before.recentOutcomes().size());
        Map<String, Object> broken = new HashMap<>(memory.exportState()); broken.put("outcomes", List.of(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> memory.importState(broken));
        assertEquals(before, memory.observe(world, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> memory.acquire(world, "#WOOD", 4, 0));
        assertEquals(before, memory.observe(world, Map.of()));
    }
    @Test void completionIsOnceAcrossRestoreAndInferenceCannotBeCreditedLater() {
        UUID world = UUID.randomUUID(); var memory = new PolicyTaskMemory();
        memory.acquire(world, "OAK_LOG", 4, 0);
        var before = memory.observe(world, Map.of("OAK_LOG", 1));
        var after = memory.observe(world, Map.of("OAK_LOG", 4));
        var completion = memory.settleCompletion(before, after, true).orElseThrow();
        assertEquals(1, completion.beforeOwned());
        assertEquals(4, completion.afterOwned());
        var restored = new PolicyTaskMemory(); restored.importState(memory.exportState());
        assertTrue(restored.settleCompletion(before, after, true).isEmpty());
        memory.acquire(world, "OAK_LOG", 4, 2);
        before = memory.observe(world, Map.of()); after = memory.observe(world, Map.of("OAK_LOG", 4));
        assertTrue(memory.settleCompletion(before, after, false).isEmpty());
        assertTrue(memory.settleCompletion(before, after, true).isEmpty());
    }
    @Test void preownedAndReplacedGoalsCannotProduceCompletionCredit() {
        UUID world = UUID.randomUUID(); var memory = new PolicyTaskMemory();
        memory.acquire(world, "OAK_LOG", 4, 0);
        var owned = memory.observe(world, Map.of("OAK_LOG", 4));
        assertTrue(memory.settleCompletion(owned, owned, true).isEmpty());
        assertTrue(memory.settleCompletion(memory.observe(world, Map.of()), owned, true).isEmpty());
        memory.acquire(world, "OAK_LOG", 4, 1);
        assertTrue(memory.settleCompletion(owned, memory.observe(world, Map.of("OAK_LOG", 4)), true).isEmpty());
        assertTrue(memory.settleCompletion(memory.observe(UUID.randomUUID(), Map.of()),
                memory.observe(world, Map.of("OAK_LOG", 4)), true).isEmpty());
    }
}
