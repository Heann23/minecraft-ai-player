package me.herry.minecraftAI.discord;

import java.util.HashMap;
import java.util.Map;
import me.herry.minecraftAI.ai.comm.GameStateSnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameStateSnapshotTest {
    private static GameStateSnapshot snapshot(double health, int food, Map<String, Integer> items) {
        return new GameStateSnapshot("Bot", "RUNNING", "IDLE", "기다리는 중", "None", "", "NORMAL", 0, 64, 0, health, food, items);
    }
    @Test void inventoryIsCopiedAndCannotCarryCustomNamesOrZeroCounts() {
        var items = new HashMap<>(Map.of("STONE", 2)); var snapshot = snapshot(20, 20, items);
        items.put("DIAMOND", 1); assertEquals(Map.of("STONE", 2), snapshot.items());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.items().put("STONE", 3));
        for (var invalid : java.util.List.of(Map.of("STONE", 0), Map.of("STONE", -1), Map.of("STONE", 1_000_001), Map.of("private item name", 1)))
            assertThrows(IllegalArgumentException.class, () -> snapshot(20, 20, invalid));
        var tooMany = new HashMap<String, Integer>(); for (int index = 0; index < 42; index++) tooMany.put("ITEM_" + index, 1);
        assertThrows(IllegalArgumentException.class, () -> snapshot(20, 20, tooMany));
    }
    @Test void invalidVitalsAndUntrustedAiNamesAreRejected() {
        for (double health : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, 1025}) assertThrows(IllegalArgumentException.class, () -> snapshot(health, 20, Map.of()));
        for (int food : new int[]{-1, 21}) assertThrows(IllegalArgumentException.class, () -> snapshot(20, food, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GameStateSnapshot("@everyone", "RUNNING", "IDLE", "", "None", "", "NORMAL", 0, 64, 0, 20, 20, Map.of()));
    }
}
