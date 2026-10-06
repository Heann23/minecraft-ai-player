package me.herry.minecraftAI.discord;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordConfigurationDiffTest {
    private static DiscordConfiguration read(Map<String, Object> values) { return DiscordConfiguration.read(values::get); }
    private static final DiscordConfiguration RUNNING = read(Map.of());
    @Test void sameFileIsReportedAsSame() {
        assertEquals(DiscordConfigurationDiff.SAME, DiscordConfigurationDiff.check(RUNNING, () -> read(Map.of())));
        assertEquals(List.of(), DiscordConfigurationDiff.changedSections(RUNNING, read(Map.of())));
    }
    @Test void everySectionChangeIsNamedWithoutShowingValues() {
        Object[][] cases = {
                {"connection", Map.<String, Object>of("enabled", true, "guild-id", "12345678901234567", "voice-channel-id", "12345678901234568")},
                {"connection", Map.<String, Object>of("character-id", "other")}, {"connection", Map.<String, Object>of("auto-connect", false)},
                {"conversation", Map.<String, Object>of("conversation.followup-seconds", 30)}, {"conversation", Map.<String, Object>of("conversation.greet-on-join", false)},
                {"backup", Map.<String, Object>of("backup.interval-seconds", 60)}, {"audio", Map.<String, Object>of("audio.end-silence-millis", 800)},
                {"audio", Map.<String, Object>of("audio.minimum-rms", 0.05)}, {"game", Map.<String, Object>of("game.target-ai", "Bot")},
                {"llm", Map.<String, Object>of("providers.llm.model", "secretmodel")}, {"speech", Map.<String, Object>of("providers.tts.voice", "secretvoice")}};
        for (Object[] changed : cases) {
            @SuppressWarnings("unchecked") var file = read((Map<String, Object>) changed[1]);
            assertEquals(List.of(changed[0]), DiscordConfigurationDiff.changedSections(RUNNING, file), String.valueOf(changed[1]));
            String report = DiscordConfigurationDiff.check(RUNNING, () -> file);
            assertTrue(report.contains("바뀐 부분: " + changed[0]) && report.contains("재시작"), report);
            assertFalse(report.contains("secret") || report.contains("Bot") || report.contains("12345678901234567"), report);
        }
    }
    @Test void severalChangedSectionsAreListedInAFixedOrder() {
        var file = read(Map.<String, Object>of("providers.llm.model", "x", "game.target-ai", "Bot", "backup.interval-seconds", 60));
        assertEquals(List.of("backup", "game", "llm"), DiscordConfigurationDiff.changedSections(RUNNING, file));
        assertTrue(DiscordConfigurationDiff.check(RUNNING, () -> file).contains("backup, game, llm"));
    }
    @Test void invalidFilesReportOnlyAFixedCodeAndKeepTheRunningSettings() {
        for (var failure : List.<Exception>of(new DiscordStartupFailure(DiscordStartupFailure.Reason.CONFIG_SYNTAX),
                new DiscordConfiguration.Invalid(DiscordStartupFailure.Reason.CONFIG_LLM, new IllegalArgumentException("secret value")))) {
            String report = DiscordConfigurationDiff.check(RUNNING, () -> { throw failure; });
            assertTrue(report.contains("discord-config-") && report.contains("실행 중인 설정 그대로"), report); assertFalse(report.contains("secret"), report);
        }
        String other = DiscordConfigurationDiff.check(RUNNING, () -> { throw new java.io.IOException("C:\\secret\\path"); });
        assertTrue(other.contains("discord-start-failed")); assertFalse(other.contains("secret"));
    }
}
