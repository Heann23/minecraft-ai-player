package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordTextStatusTest {
    @TempDir Path directory;
    private DiscordSettings settings() {
        return new DiscordSettings(true, "TEST_TOKEN", "12345678901234567", "12345678901234568", "herry", false, 60000, 32,
                new DiscordMemoryStore.BackupPolicy(false, 600000, 24, 7, 128L * 1024 * 1024));
    }
    private DiscordMemoryStore store() throws Exception { return new DiscordMemoryStore(directory, settings().backup(), () -> 1000); }
    private static DiscordTextConversation.Status awaitStatus(DiscordTextConversation text, Predicate<DiscordTextConversation.Status> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        var status = text.status();
        while (!expected.test(status) && System.nanoTime() < deadline) { Thread.sleep(2); status = text.status(); }
        assertTrue(expected.test(status), status.toString()); return status;
    }
    @Test void completionCountsPreparationWithoutClaimingDeliveryAndContainsNoPrivateContent() throws Exception {
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> "PRIVATE_ANSWER", () -> 1000)) {
            var ready = text.reply("PRIVATE_USER", "PRIVATE_INPUT", "one").get(3, TimeUnit.SECONDS);
            assertTrue(text.current(ready)); var prepared = awaitStatus(text, status -> status.completed() == 1 && !status.running());
            assertEquals(0, prepared.cancelled()); assertEquals(0, prepared.failed()); assertEquals(0, prepared.rejected());
            assertFalse(prepared.describe().contains("PRIVATE")); text.discard(ready);
            assertEquals(1, text.status().completed());
        }
    }
    @Test void rejectionCancellationAndQueueCapacityAreReportedSeparately() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            if (request.turn().userId().equals("A")) { entered.countDown(); release.await(); }
            return "답변";
        }, () -> 1000)) {
            var first = text.reply("A", "질문", "one"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = text.reply("B", "질문", "one");
            for (String user : java.util.List.of("C", "D", "E")) text.reply(user, "질문", "one");
            assertTrue(text.reply("F", "질문", "one").isCompletedExceptionally());
            var full = text.status(); assertTrue(full.running()); assertEquals(4, full.queued()); assertEquals(1, full.rejected()); assertEquals(0, full.failed());
            queued.cancel(true); assertEquals(3, text.status().queued()); assertEquals(1, text.status().cancelled());
            first.cancel(true); release.countDown();
            var finished = awaitStatus(text, status -> status.completed() == 3 && !status.running() && status.queued() == 0);
            assertEquals(2, finished.cancelled()); assertEquals(1, finished.rejected()); assertEquals(0, finished.failed());
        } finally { release.countDown(); }
    }
    @Test void providerFailureAndShutdownHaveNoInputOrExceptionBodyInStatus() throws Exception {
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            throw new IllegalStateException("PRIVATE_PROVIDER_EXCEPTION");
        }, () -> 1000)) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> text.reply("PRIVATE_USER", "PRIVATE_INPUT", "one").get(3, TimeUnit.SECONDS));
            var failure = awaitStatus(text, status -> status.failed() == 1 && !status.running());
            assertEquals(0, failure.completed()); assertEquals(0, failure.cancelled()); assertFalse(failure.describe().contains("PRIVATE"));
            text.close(); assertTrue(text.status().closed()); assertTrue(text.status().describe().contains("종료됨"));
        }
    }
}
