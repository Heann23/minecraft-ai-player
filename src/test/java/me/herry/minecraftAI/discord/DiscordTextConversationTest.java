package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordTextConversationTest {
    @TempDir Path directory;
    private DiscordSettings settings() { return new DiscordSettings(true, "TEST_TOKEN", "12345678901234567", "12345678901234568", "herry", false, 60000, 32,
            new DiscordMemoryStore.BackupPolicy(false, 600000, 24, 7, 128L*1024*1024)); }
    private DiscordMemoryStore store() throws Exception { return new DiscordMemoryStore(directory, settings().backup(), () -> 1000); }
    @Test void onlyDeliveredOwnRepliesEnterPrivateFollowupContext() throws Exception {
        var input = new AtomicReference<ResponsePipeline.Request>();
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> { input.set(request); return "답변이에요."; }, () -> 1000)) {
            var first = text.reply("A", "안녕하세요", "one").get(3, TimeUnit.SECONDS);
            assertTrue(text.current(first)); text.submitted(first);
            var second = text.reply("A", "그다음은요", "two").get(3, TimeUnit.SECONDS);
            assertTrue(input.get().context().stream().anyMatch(ConversationTurns.Line::assistant)); text.discard(second);
            var other = text.reply("B", "제 질문이에요", "three").get(3, TimeUnit.SECONDS);
            assertEquals(1, input.get().context().size()); assertEquals("B", input.get().turn().userId()); text.submitted(other);
            var third = text.reply("A", "다시요", "four").get(3, TimeUnit.SECONDS);
            assertEquals(1, input.get().context().stream().filter(ConversationTurns.Line::assistant).count()); text.submitted(third);
        }
    }
    @Test void deletionDuringModelWorkRejectsLateReplyAndClearsContext() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            entered.countDown(); release.await(); return "늦은 답변";
        }, () -> 1000)) {
            var reply = text.reply("A", "질문", "one"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            text.forget("A"); release.countDown(); assertThrows(CancellationException.class, () -> reply.get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }
    @Test void invalidInputAndOversizedReplyCannotReachDiscord() throws Exception {
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> "가".repeat(1901), () -> 1000)) {
            assertThrows(IllegalArgumentException.class, () -> text.reply("A", " ", "one"));
            assertThrows(IllegalArgumentException.class, () -> text.reply("A", "가".repeat(1001), "one"));
            var failure = assertThrows(ExecutionException.class, () -> text.reply("A", "질문", "two").get(3, TimeUnit.SECONDS));
            assertEquals("text conversation unavailable", failure.getCause().getMessage());
        }
    }
    @Test void shutdownCompletesOutstandingRepliesAndRejectsNewWork() throws Exception {
        var entered = new CountDownLatch(1);
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            entered.countDown(); new CountDownLatch(1).await(); return "답변";
        }, () -> 1000)) {
            var result = text.reply("A", "질문", "one"); assertTrue(entered.await(3, TimeUnit.SECONDS)); text.close();
            assertThrows(CancellationException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> text.reply("A", "질문", "two").get(3, TimeUnit.SECONDS));
        }
    }
    @Test void textUsesOnlyCurrentUsersConfirmedMemoryWithoutVoiceMembership() throws Exception {
        var input = new AtomicReference<ResponsePipeline.Request>();
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> { input.set(request); return "반가워요."; }, () -> 1000)) {
            for (String user : java.util.List.of("A", "B")) store.remember(new DiscordMemory.Key(
                    new DiscordMemory.Subject(settings().guildId(), "herry", user), DiscordMemory.Kind.NAME, "", "preferred"),
                    user.equals("A") ? "민수" : "지수", DiscordMemory.Evidence.EXPLICIT, "slash-name", 0).get(3, TimeUnit.SECONDS);
            var reply = text.reply("A", "안녕하세요", "one").get(3, TimeUnit.SECONDS);
            assertEquals(1, input.get().memory().size()); assertEquals("민수", input.get().memory().getFirst().value());
            text.submitted(reply);
        }
    }
    @Test void queueRemainsBoundedWhileOneInferenceIsBlocked() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            entered.countDown(); release.await(); return "답변";
        }, () -> 1000)) {
            text.reply("A", "질문", "one"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            for (String user : java.util.List.of("B", "C", "D", "E")) text.reply(user, "질문", "one");
            var failure = assertThrows(ExecutionException.class, () -> text.reply("F", "질문", "one").get(3, TimeUnit.SECONDS));
            assertEquals("text conversation busy", failure.getCause().getMessage());
        } finally { release.countDown(); }
    }
    @Test void newQuestionInterruptsOldInferenceAndOnlyDeliveredAnswerEntersContext() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var input = new AtomicReference<ResponsePipeline.Request>();
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            input.set(request);
            if (DialogueContext.currentInput(request).equals("첫 질문")) {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
            }
            return "새 답변";
        }, () -> 1000)) {
            var old = text.reply("A", "첫 질문", "one"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var fresh = text.reply("A", "새 질문", "two").get(3, TimeUnit.SECONDS);
            assertTrue(interrupted.await(3, TimeUnit.SECONDS)); assertTrue(old.isCancelled());
            assertTrue(text.current(fresh)); assertEquals("새 질문", DialogueContext.currentInput(input.get()));
            assertFalse(input.get().context().stream().anyMatch(ConversationTurns.Line::assistant)); text.submitted(fresh);
            text.reply("A", "후속 질문", "three").get(3, TimeUnit.SECONDS);
            assertEquals(1, input.get().context().stream().filter(ConversationTurns.Line::assistant).count());
        }
    }
    @Test void cancellingQueuedQuestionReleasesCapacityBeforeBlockedInferenceFinishes() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            if (request.turn().userId().equals("A")) { entered.countDown(); release.await(); }
            return "답변";
        }, () -> 1000)) {
            text.reply("A", "질문", "one"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = text.reply("B", "이전 질문", "one");
            for (String user : java.util.List.of("C", "D", "E")) text.reply(user, "질문", "one");
            assertTrue(queued.cancel(true));
            var replacement = text.reply("B", "새 질문", "two"); assertFalse(replacement.isCompletedExceptionally());
            release.countDown(); assertTrue(text.current(replacement.get(3, TimeUnit.SECONDS)));
        } finally { release.countDown(); }
    }
    @Test void forgetResetAndEvictionInterruptActiveWork() throws Exception {
        for (String operation : java.util.List.of("forget", "reset", "evict")) {
            var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
            try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
                if (request.turn().userId().equals("A")) {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
                }
                return "답변";
            }, () -> 1000)) {
                var old = text.reply("A", "질문", "one"); assertTrue(entered.await(3, TimeUnit.SECONDS));
                switch (operation) {
                    case "forget" -> text.forget("A");
                    case "reset" -> text.reset();
                    case "evict" -> { for (int i = 0; i < 32; i++) text.reply("user" + i, "질문", "one"); }
                }
                assertTrue(old.isCancelled(), operation); assertTrue(interrupted.await(3, TimeUnit.SECONDS), operation);
            }
        }
    }
    @Test void callerCancellationRetiresTokenAndInterruptsInference() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var input = new AtomicReference<ResponsePipeline.Request>();
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            input.set(request); entered.countDown();
            try { new CountDownLatch(1).await(); return "늦은 답변"; }
            catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
        }, () -> 1000)) {
            var result = text.reply("A", "질문", "one"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(result.cancel(true)); assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertFalse(input.get().current().getAsBoolean());
        }
    }
    @Test void providerIgnoringInterruptionCannotOverlapOrPublishRetiredReply() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            if (DialogueContext.currentInput(request).equals("첫 질문")) {
                entered.countDown();
                boolean done = false;
                while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { /* Deliberately stubborn test provider. */ }
                assertFalse(request.current().getAsBoolean()); return "폐기할 답변";
            }
            secondEntered.countDown(); return "새 답변";
        }, () -> 1000)) {
            var old = text.reply("A", "첫 질문", "one"); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var fresh = text.reply("A", "새 질문", "two"); assertTrue(old.isCancelled());
            assertEquals(1, secondEntered.getCount()); assertFalse(fresh.isDone());
            release.countDown(); assertEquals("새 답변", fresh.get(3, TimeUnit.SECONDS).text());
        } finally { release.countDown(); }
    }
}
