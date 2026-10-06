package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordTextPreferenceTest {
    @TempDir Path directory;
    private DiscordSettings settings() {
        return new DiscordSettings(true, "TEST_TOKEN", "12345678901234567", "12345678901234568", "herry", false, 60000, 32,
                new DiscordMemoryStore.BackupPolicy(false, 600000, 24, 7, 128L * 1024 * 1024));
    }
    private DiscordMemory.Subject subject(String user) { return new DiscordMemory.Subject(settings().guildId(), "herry", user); }
    private DiscordMemoryStore store() throws Exception { return new DiscordMemoryStore(directory, settings().backup(), () -> 1000); }
    @Test void explicitTextStopsJokesWithoutModelAndPersistsOnlyOwnMinimalSetting() throws Exception {
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> { throw new AssertionError("No model for preference"); }, () -> 1000)) {
            var reply = text.reply("A", "해리님, 장난 그만해 주세요", "one").get(3, TimeUnit.SECONDS);
            assertEquals("미안해요. 장난은 멈추고 담백하게 이야기할게요.", reply.text()); text.submitted(reply);
            var facts = store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS);
            assertEquals(1, facts.size()); assertEquals(DiscordMemory.Kind.AVOID_JOKE, facts.getFirst().key().kind());
            assertEquals("AVOID", facts.getFirst().value()); assertTrue(facts.getFirst().sourceId().startsWith("text-"));
            assertFalse(facts.getFirst().sourceId().contains("장난"));
            assertTrue(store.visible(subject("B"), Set.of("A", "B")).get(3, TimeUnit.SECONDS).isEmpty());
        }
        try (var reopened = store()) { assertEquals("AVOID", reopened.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).getFirst().value()); }
    }
    @Test void newExplicitPermissionChangesOnlyJokePreferenceAndCasualStyleRequiresSeparateAgreement() throws Exception {
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> { throw new AssertionError("No model for preference"); }, () -> 1000)) {
            var refusal = text.reply("A", "장난하지 마세요", "one").get(3, TimeUnit.SECONDS); text.submitted(refusal);
            var allowed = text.reply("A", "다시 장난해도 돼요", "two").get(3, TimeUnit.SECONDS);
            assertTrue(allowed.text().contains("기억할게요")); text.submitted(allowed);
            var own = store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS);
            assertEquals(1, own.size()); assertEquals("ALLOWED", own.getFirst().value());
            store.remember(new DiscordMemory.Key(subject("A"), DiscordMemory.Kind.SPEECH_AGREEMENT, "", "casual"), "ALLOWED", DiscordMemory.Evidence.EXPLICIT, "slash-agreement", 0).get(3, TimeUnit.SECONDS);
            assertEquals("미안해. 장난은 멈추고 담백하게 이야기할게.", text.reply("A", "농담이 불편해요", "three").get(3, TimeUnit.SECONDS).text());
        }
    }
    @Test void quotedOrGeneratedRequestCannotBecomeDurableSetting() throws Exception {
        var captured = new AtomicReference<ResponsePipeline.Request>();
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> {
            captured.set(request); return "장난 그만해 주세요";
        }, () -> 1000)) {
            var quoted = text.reply("A", "친구가 장난 그만해 주세요라고 했어요", "one").get(3, TimeUnit.SECONDS); text.submitted(quoted);
            text.reply("A", "그 말을 설명해 주세요", "two").get(3, TimeUnit.SECONDS);
            assertTrue(captured.get().memory().isEmpty()); assertTrue(store.snapshot().get(3, TimeUnit.SECONDS).facts().isEmpty());
        }
    }
    @Test void deletionDuringAdmissionCannotStoreOrAcknowledgeRetiredPreference() throws Exception {
        var admitted = new CountDownLatch(1); var release = new CompletableFuture<Void>();
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> "일반 답변", () -> 1000,
                (user, current) -> { admitted.countDown(); return release; })) {
            var old = text.reply("A", "장난이 불편해요", "one"); assertTrue(admitted.await(3, TimeUnit.SECONDS));
            text.forget("A"); store.forget(subject("A")).get(3, TimeUnit.SECONDS); release.complete(null);
            assertThrows(java.util.concurrent.CancellationException.class, () -> old.get(3, TimeUnit.SECONDS));
            var fresh = text.reply("A", "다시 안녕하세요", "two").get(3, TimeUnit.SECONDS); text.submitted(fresh);
            assertTrue(store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).isEmpty());
        } finally { release.complete(null); }
    }
    @Test void admissionFailureCannotPersistOrClaimAppliedPreference() throws Exception {
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> "unused", () -> 1000,
                (user, current) -> CompletableFuture.failedFuture(new IllegalStateException("session unavailable")))) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> text.reply("A", "장난이 불편해요", "one").get(3, TimeUnit.SECONDS));
            assertTrue(store.snapshot().get(3, TimeUnit.SECONDS).facts().isEmpty());
        }
    }
}
