package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordPersonalMemoryTest {
    @TempDir Path directory;
    private DiscordSettings settings() { return new DiscordSettings(true, "TEST_TOKEN", "12345678901234567", "12345678901234568", "herry", true, 60_000, 32,
            new DiscordMemoryStore.BackupPolicy(false, 600_000, 24, 7, 128L * 1024 * 1024)); }
    private DiscordMemory.Subject subject(String user) { return new DiscordMemory.Subject(settings().guildId(), "herry", user); }
    private DiscordMemoryStore store() throws Exception { return new DiscordMemoryStore(directory, settings().backup(), () -> 1000); }
    private DiscordSession session(DiscordMemoryStore store) { return new DiscordSession(settings(), store, (pcm, language) -> new SpeechRecognitionWorker.Recognition("해리야 안녕", false),
            request -> "안녕하세요.", text -> new byte[PcmAudio.FRAME_BYTES], code -> {}, () -> 1000, () -> 1000, false); }
    @Test void explicitNameAndConsentSurviveRestartAndBelongOnlyToTheirUser() throws Exception {
        var memory = store();
        try (var session = session(memory)) {
            session.confirmedName("A", "민수", "name-1").get(3, TimeUnit.SECONDS);
            session.confirmedSpeechStyle("A", true, "speech-1").get(3, TimeUnit.SECONDS);
            assertTrue(memory.visible(subject("B"), Set.of("A", "B")).get().isEmpty());
        }
        try (var reopened = store()) {
            var facts = reopened.visible(subject("A"), Set.of("A")).get(); assertEquals(2, facts.size());
            assertTrue(facts.stream().anyMatch(fact -> fact.value().equals("민수") && fact.sourceId().equals("slash-name-1")));
            assertTrue(facts.stream().anyMatch(fact -> fact.value().equals("ALLOWED") && fact.evidence() == DiscordMemory.Evidence.EXPLICIT));
        }
    }
    @Test void correctionAndRefusalReplacePreviousExplicitValues() throws Exception {
        var memory = store();
        try (var session = session(memory)) {
            session.confirmedName("A", "민수", "name-1").get(); session.confirmedName("A", "지수", "name-2").get();
            session.confirmedSpeechStyle("A", true, "speech-1").get(); session.confirmedSpeechStyle("A", false, "speech-2").get();
            var facts = memory.visible(subject("A"), Set.of("A")).get(); assertEquals(2, facts.size());
            assertEquals(Set.of("지수", "REFUSED"), facts.stream().map(DiscordMemory.Fact::value).collect(java.util.stream.Collectors.toSet()));
        }
    }
    @Test void personalForgetErasesConfirmedInputsWithoutTouchingAnotherUser() throws Exception {
        var memory = store();
        try (var session = session(memory)) {
            session.confirmedName("A", "민수", "name-a").get(); session.confirmedName("B", "지수", "name-b").get();
            session.forget("A").get(); assertTrue(memory.visible(subject("A"), Set.of("A", "B")).get().isEmpty());
            assertEquals("지수", memory.visible(subject("B"), Set.of("A", "B")).get().getFirst().value());
        }
    }
    @Test void invalidOrQuotedNamesAndUnboundedSourceAreRejectedBeforeMutation() throws Exception {
        var memory = store();
        try (var session = session(memory)) {
            for (String name : new String[]{"", "친구 이름은 민수", "\"민수\"", "<@1234>", "가".repeat(21)})
                assertThrows(IllegalArgumentException.class, () -> session.confirmedName("A", name, "input"));
            assertThrows(IllegalArgumentException.class, () -> session.confirmedName("A", "민수", "id".repeat(50)));
            assertTrue(memory.visible(subject("A"), Set.of("A")).get().isEmpty());
            session.close(); assertThrows(java.util.concurrent.CompletionException.class, () -> session.confirmedName("A", "민수", "input").join());
        }
    }
}
