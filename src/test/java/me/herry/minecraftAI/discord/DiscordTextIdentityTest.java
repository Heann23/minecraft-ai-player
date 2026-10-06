package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordTextIdentityTest {
    @TempDir Path directory;
    private DiscordSettings settings() {
        return new DiscordSettings(true, "TEST_TOKEN", "12345678901234567", "12345678901234568", "herry", false, 60000, 32,
                new DiscordMemoryStore.BackupPolicy(false, 600000, 24, 7, 128L * 1024 * 1024));
    }
    private DiscordMemory.Subject subject(String user) { return new DiscordMemory.Subject(settings().guildId(), "herry", user); }
    private DiscordMemoryStore store() throws Exception { return new DiscordMemoryStore(directory, settings().backup(), () -> 1000); }
    @Test void directNameIsConfirmedBeforeFollowupAndPersistsOnlyForItsSpeaker() throws Exception {
        var input = new AtomicReference<ResponsePipeline.Request>();
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> { input.set(request); return "일반 답변"; }, () -> 1000)) {
            var introduced = text.reply("A", "해리님, 제 이름은 민수예요", "one").get(3, TimeUnit.SECONDS);
            assertEquals("민수님으로 부를게요.", introduced.text()); assertNull(input.get()); text.submitted(introduced);
            text.reply("A", "다음 이야기요", "two").get(3, TimeUnit.SECONDS);
            assertEquals("민수", input.get().memory().getFirst().value());
            var other = text.reply("B", "안녕하세요", "three").get(3, TimeUnit.SECONDS); assertTrue(input.get().memory().isEmpty()); text.submitted(other);
            var corrected = text.reply("A", "성훈이라고 불러 주세요", "four").get(3, TimeUnit.SECONDS);
            assertEquals("성훈님으로 부를게요.", corrected.text()); text.submitted(corrected);
            assertEquals(1, store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).size());
        }
        try (var reopened = store()) { assertEquals("성훈", reopened.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).getFirst().value()); }
    }
    @Test void explicitSpeechPermissionAndRefusalCannotComeFromBareYesOrModelQuestion() throws Exception {
        var input = new AtomicReference<ResponsePipeline.Request>();
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> { input.set(request); return "반말해도 될까요?"; }, () -> 1000)) {
            var first = text.reply("A", "이야기해 주세요", "one").get(3, TimeUnit.SECONDS); text.submitted(first);
            var yes = text.reply("A", "네", "two").get(3, TimeUnit.SECONDS); text.submitted(yes);
            assertTrue(store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).isEmpty());
            var permitted = text.reply("A", "해리님, 저한테 반말해도 돼요", "three").get(3, TimeUnit.SECONDS);
            assertEquals("알겠어. 나한테 반말을 허락한 걸로 기억할게.", permitted.text()); text.submitted(permitted);
            var refused = text.reply("A", "존댓말로 말해 주세요", "four").get(3, TimeUnit.SECONDS);
            assertEquals("알겠어요. 앞으로 존댓말로 이야기할게요.", refused.text()); text.submitted(refused);
            text.reply("A", "다시 질문이에요", "five").get(3, TimeUnit.SECONDS);
            assertEquals("REFUSED", input.get().memory().getFirst().value());
            assertEquals(1, store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS).size());
        }
    }
    @Test void askingPermissionCannotOverrideExistingJokeBoundaryOrIntroduceNameFromQuote() throws Exception {
        try (var store = store(); var text = new DiscordTextConversation(settings(), store, request -> "일반 답변", () -> 1000)) {
            var stop = text.reply("A", "장난이 불편해요", "one").get(3, TimeUnit.SECONDS); text.submitted(stop);
            text.reply("A", "다시 장난해도 돼요?", "two").get(3, TimeUnit.SECONDS);
            text.reply("A", "\"제 이름은 민수예요\"라고 말했어요", "three").get(3, TimeUnit.SECONDS);
            var facts = store.visible(subject("A"), Set.of("A")).get(3, TimeUnit.SECONDS);
            assertEquals(1, facts.size()); assertEquals(DiscordMemory.Kind.AVOID_JOKE, facts.getFirst().key().kind()); assertEquals("AVOID", facts.getFirst().value());
        }
    }
}
