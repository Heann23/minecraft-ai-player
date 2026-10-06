package me.herry.minecraftAI.discord;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import me.herry.minecraftAI.ai.comm.GameStateSnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordGameStateTest {
    private static GameStateSnapshot snapshot(String ai) {
        return new GameStateSnapshot(ai, "RUNNING", "MINE_STONE", "돌을 캐는 중", "BreakBlock", "돌이 필요해서요", "NORMAL", 1, 64, -2, 19.5, 18, Map.of("COBBLESTONE", 12));
    }
    @Test void explicitTargetDisablesWorldReadsUntilConfiguredAndWrongTargetNeverLeaks() {
        var reads = new AtomicInteger(); var target = new AtomicReference<String>();
        try (var game = new DiscordGameState(() -> true, name -> { reads.incrementAndGet(); target.set(name); return snapshot("Bot"); }, () -> 1000, ignored -> {})) {
            game.refresh(); assertEquals(0, reads.get()); assertEquals(DiscordGameState.Code.NOT_CONFIGURED, game.view().code());
            game.configure("Bot"); game.refresh(); assertEquals("Bot", target.get()); assertEquals(1, reads.get());
            assertEquals("Bot", game.view().snapshot().aiName());
            game.configure("Other"); assertNull(game.view().snapshot()); game.refresh();
            assertEquals(DiscordGameState.Code.UNAVAILABLE, game.view().code()); assertNull(game.view().snapshot());
        }
    }
    @Test void snapshotsExpireAfterServerStallAndClockReversalAndRefreshMakesThemCurrent() {
        var clock = new AtomicLong(1000);
        try (var game = new DiscordGameState(() -> true, ignored -> snapshot("Bot"), clock::get, ignored -> {})) {
            game.configure("Bot"); game.refresh(); clock.set(3500); assertNotNull(game.view().snapshot());
            clock.set(3501); assertEquals(DiscordGameState.Code.STALE, game.view().code()); assertNull(game.view().snapshot());
            game.refresh(); assertEquals(DiscordGameState.Code.FRESH, game.view().code());
            clock.set(3500); assertEquals(DiscordGameState.Code.STALE, game.view().code()); assertNull(game.view().snapshot());
        }
    }
    @Test void missingAndFailedReadsNeverRetainEarlierInventoryAndFailureDiagnosticsContainNoExceptionText() {
        var result = new AtomicReference<GameStateSnapshot>(snapshot("Bot")); var fail = new AtomicBoolean(); var messages = new ArrayList<String>();
        try (var game = new DiscordGameState(() -> true, name -> { if (fail.get()) throw new IllegalStateException("private world name"); return result.get(); }, () -> 1000, messages::add)) {
            game.configure("Bot"); game.refresh(); assertEquals(12, game.view().snapshot().items().get("COBBLESTONE"));
            result.set(null); game.refresh(); assertEquals(DiscordGameState.Code.NOT_FOUND, game.view().code()); assertNull(game.view().snapshot());
            fail.set(true); game.refresh(); game.refresh(); assertEquals(java.util.List.of("discord-game-state-unavailable"), messages);
            assertEquals(DiscordGameState.Code.UNAVAILABLE, game.view().code()); assertNull(game.view().snapshot());
        }
    }
    @Test void onlyMainThreadMayReadAndClosePreventsFurtherPublication() {
        var main = new AtomicBoolean(false); var reads = new AtomicInteger();
        var game = new DiscordGameState(main::get, name -> { reads.incrementAndGet(); return snapshot(name); }, () -> 1000, ignored -> {});
        game.configure("Bot"); assertThrows(IllegalStateException.class, game::refresh); assertEquals(0, reads.get());
        main.set(true); game.refresh(); assertNotNull(game.view().snapshot()); game.close(); game.configure("Bot"); game.refresh();
        assertEquals(1, reads.get()); assertEquals(DiscordGameState.Code.STOPPED, game.view().code()); assertNull(game.view().snapshot());
    }
    @Test void diagnosticFailureDoesNotEscapeTheServerTask() {
        try (var game = new DiscordGameState(() -> true, name -> { throw new IllegalStateException(); }, () -> 1000, code -> { throw new IllegalStateException(); })) {
            game.configure("Bot"); assertDoesNotThrow(game::refresh); assertDoesNotThrow(game::refresh);
            assertEquals(1, game.diagnosticFailures()); assertEquals(DiscordGameState.Code.UNAVAILABLE, game.view().code());
        }
    }
}
