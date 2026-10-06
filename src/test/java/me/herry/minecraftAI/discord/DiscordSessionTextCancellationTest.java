package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordSessionTextCancellationTest {
    @TempDir Path directory;
    private DiscordSession session(ResponsePipeline.Model model) throws Exception {
        var settings = new DiscordSettings(true, "TEST_TOKEN", "12345678901234567", "12345678901234568", "herry", false, 60000, 32,
                new DiscordMemoryStore.BackupPolicy(false, 600000, 24, 7, 128L * 1024 * 1024));
        return new DiscordSession(settings, new DiscordMemoryStore(directory, settings.backup(), () -> 1000),
                (pcm, language) -> { throw new AssertionError("No voice expected"); }, model, text -> new byte[PcmAudio.FRAME_BYTES],
                code -> fail(code), () -> 1000, () -> 1000, false);
    }
    @Test void callerCancellationReachesTextWorkerAndRetiresModelRequest() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var oldRequest = new AtomicReference<ResponsePipeline.Request>();
        try (var session = session(request -> {
            if (DialogueContext.currentInput(request).equals("옛 질문")) {
                oldRequest.set(request); entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
            }
            assertFalse(request.context().stream().anyMatch(ConversationTurns.Line::assistant)); return "새 답변";
        })) {
            var old = session.textReply("A", "옛 질문", "old"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(old.cancel(true)); assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertFalse(oldRequest.get().current().getAsBoolean());
            var fresh = session.textReply("A", "새 질문", "new").get(3, TimeUnit.SECONDS);
            assertTrue(session.textCurrent(fresh)); session.textSubmitted(fresh);
        }
    }
    @Test void newQuestionPropagatesCancellationToCallerWithoutPublishingLateAnswer() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var session = session(request -> {
            if (DialogueContext.currentInput(request).equals("옛 질문")) {
                entered.countDown();
                boolean done = false;
                while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { /* Stubborn provider fixture. */ }
                return "폐기 답변";
            }
            return "새 답변";
        })) {
            var old = session.textReply("A", "옛 질문", "old"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var fresh = session.textReply("A", "새 질문", "new");
            assertThrows(CancellationException.class, () -> old.get(3, TimeUnit.SECONDS));
            assertFalse(fresh.isDone()); release.countDown();
            assertEquals("새 답변", fresh.get(3, TimeUnit.SECONDS).text());
        } finally { release.countDown(); }
    }
}
