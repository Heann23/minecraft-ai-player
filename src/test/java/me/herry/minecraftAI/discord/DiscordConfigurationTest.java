package me.herry.minecraftAI.discord;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordConfigurationTest {
    @TempDir Path directory;
    @Test void createsDefaultFileOnceAndPreservesQuotedIdsFromExistingFile() throws Exception {
        var created = DiscordConfiguration.load(directory, () -> new ByteArrayInputStream("enabled: false\n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(created.discord().enabled()); assertEquals(0.01, created.minimumRms());
        assertTrue(created.greetOnJoin());
        assertEquals(1000, created.capturePolicy().normalPauseMillis());
        assertEquals(1500, created.capturePolicy().continuationPauseMillis());
        Files.writeString(directory.resolve("discord.yml"), "enabled: true\nguild-id: '12345678901234567'\nvoice-channel-id: '12345678901234568'\n");
        var loaded = DiscordConfiguration.load(directory, () -> { fail("existing configuration overwritten"); return null; });
        assertTrue(loaded.discord().enabled()); assertEquals("12345678901234567", loaded.discord().guildId());
    }
    @Test void unquotedSnowflakeMalformedYamlAndOversizedFilesAreRejected() throws Exception {
        Path file = directory.resolve("discord.yml");
        Files.writeString(file, "guild-id: 12345678901234567\n");
        assertThrows(IllegalArgumentException.class, () -> DiscordConfiguration.load(directory, () -> null));
        Files.writeString(file, "enabled: [broken\n"); assertThrows(IOException.class, () -> DiscordConfiguration.load(directory, () -> null));
        Files.write(file, new byte[]{(byte) 0xff}); assertThrows(IOException.class, () -> DiscordConfiguration.load(directory, () -> null));
        Files.writeString(file, " ".repeat(65_537)); assertThrows(IOException.class, () -> DiscordConfiguration.load(directory, () -> null));
    }
    @Test void missingResourceAndDirectoryInPlaceOfConfigurationFailClearly() throws Exception {
        assertThrows(IOException.class, () -> DiscordConfiguration.load(directory, () -> null));
        Files.createDirectory(directory.resolve("discord.yml"));
        assertThrows(IOException.class, () -> DiscordConfiguration.load(directory, () -> null));
    }
    @Test void energyThresholdIsFinitePositiveAndCannotBeMistakenForConfidence() {
        for (Object value : new Object[]{"0.01", true, 0, -0.2, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> DiscordConfiguration.read(Map.of("audio.minimum-rms", value)::get));
        assertEquals(0.02, DiscordConfiguration.read(Map.of("audio.minimum-rms", 0.02)::get).minimumRms());
    }
    @Test void pauseSettingRejectsWrongTypesAndBoundsAndDrivesCapturePolicy() {
        for (Object value : new Object[]{"1000", true, 99, 2501, 500.5, Double.NaN})
            assertThrows(IllegalArgumentException.class, () -> DiscordConfiguration.read(Map.of("audio.end-silence-millis", value)::get));
        var configured = DiscordConfiguration.read(Map.of("audio.end-silence-millis", 800)::get);
        assertEquals(800, configured.capturePolicy().normalPauseMillis());
        assertEquals(1300, configured.capturePolicy().continuationPauseMillis());
    }
    @Test void joinGreetingSwitchMustBeBoolean() {
        assertFalse(DiscordConfiguration.read(Map.of("conversation.greet-on-join", false)::get).greetOnJoin());
        for (Object invalid : new Object[]{"true", 1})
            assertThrows(IllegalArgumentException.class, () -> DiscordConfiguration.read(Map.of("conversation.greet-on-join", invalid)::get));
    }
    @Test void gameMappingIsOptionalExplicitAndUsesOnlyValidAiNames() {
        assertEquals("", DiscordConfiguration.read(Map.<String, Object>of()::get).targetAi());
        assertEquals("Bot", DiscordConfiguration.read(Map.of("game.target-ai", "Bot")::get).targetAi());
        for (Object invalid : new Object[]{123, true, "해리", "@everyone", "a", "with spaces", "a".repeat(17)})
            assertThrows(IllegalArgumentException.class, () -> DiscordConfiguration.read(Map.of("game.target-ai", invalid)::get));
    }
    private String loadFailure() {
        return assertThrows(DiscordStartupFailure.class, () -> DiscordConfiguration.load(directory, () -> null)).diagnostic();
    }
    @Test void fileProblemsAreReportedByFixedCodesOnly() throws Exception {
        Path file = directory.resolve("discord.yml");
        Files.writeString(file, "enabled: [broken\n"); assertEquals("discord-config-syntax", loadFailure());
        Files.writeString(file, " ".repeat(65_537)); assertEquals("discord-config-size", loadFailure());
        Files.write(file, new byte[]{(byte) 0xff}); assertEquals("discord-config-unreadable", loadFailure());
        Files.delete(file); Files.createDirectory(file); assertEquals("discord-config-unreadable", loadFailure());
        Files.delete(file); assertEquals("discord-config-unreadable", loadFailure());
    }
    @Test void invalidValuesNameOnlyTheSectionWithAFixedCode() {
        Object[][] cases = {
                {"guild-id", 12345678901234567L, "discord-config-invalid-connection"}, {"token-env", "lowercase", "discord-config-invalid-connection"},
                {"conversation.followup-seconds", 0, "discord-config-invalid-conversation"}, {"conversation.greet-on-join", "yes", "discord-config-invalid-conversation"},
                {"backup.interval-seconds", "soon", "discord-config-invalid-backup"}, {"backup.max-megabytes", 1, "discord-config-invalid-backup"},
                {"providers.llm.model", "bad model!", "discord-config-invalid-llm"}, {"providers.llm.timeout-seconds", 0, "discord-config-invalid-llm"},
                {"providers.stt.timeout-seconds", 999, "discord-config-invalid-speech"}, {"providers.tts.voice", "bad voice!", "discord-config-invalid-speech"},
                {"audio.minimum-rms", "loud", "discord-config-invalid-audio"}, {"audio.end-silence-millis", 5, "discord-config-invalid-audio"},
                {"game.target-ai", "@everyone", "discord-config-invalid-game"}};
        for (Object[] invalid : cases) {
            var failure = assertThrows(DiscordConfiguration.Invalid.class, () -> DiscordConfiguration.read(Map.of((String) invalid[0], invalid[1])::get), (String) invalid[0]);
            assertEquals(invalid[2], failure.diagnostic(), (String) invalid[0]);
            assertEquals(invalid[2], failure.getMessage()); assertTrue(failure instanceof IllegalArgumentException);
        }
    }
    @Test void tokenInTheFileIsTrimmedValidatedAndNeverShownInText() {
        String token = "A".repeat(24) + "." + "B".repeat(6) + "." + "C".repeat(27);
        var config = read(Map.of("token", "  " + token + " "));
        assertEquals(token, config.token()); assertFalse(config.toString().contains(token)); assertTrue(config.toString().contains("discord.yml"));
        assertEquals("", read(Map.of()).token()); assertTrue(read(Map.of()).toString().contains("environment"));
        for (Object bad : new Object[]{"short", "a".repeat(29), "a".repeat(201), token + " extra", "토큰".repeat(20), 12345678901234567L, true}) {
            var failure = assertThrows(DiscordConfiguration.Invalid.class, () -> read(Map.of("token", bad)), String.valueOf(bad));
            assertEquals("discord-config-invalid-connection", failure.diagnostic()); assertFalse(String.valueOf(failure.getMessage()).contains(String.valueOf(bad)));
        }
    }
    private static DiscordConfiguration read(Map<String, Object> values) { return DiscordConfiguration.read(values::get); }
    @Test void minecraftChatTalkIsOnByDefaultAndItsTypeIsChecked() {
        assertTrue(read(Map.of()).minecraftChat());
        assertFalse(read(Map.<String, Object>of("conversation.minecraft-chat", false)).minecraftChat());
        assertEquals("discord-config-invalid-conversation", assertThrows(DiscordConfiguration.Invalid.class,
                () -> read(Map.<String, Object>of("conversation.minecraft-chat", "yes"))).diagnostic());
    }
    @Test void unquotedSnowflakeFromAFileIsReportedAsConnection() throws Exception {
        Files.writeString(directory.resolve("discord.yml"), "guild-id: 12345678901234567\n");
        assertEquals("discord-config-invalid-connection", assertThrows(DiscordConfiguration.Invalid.class, () -> DiscordConfiguration.load(directory, () -> null)).diagnostic());
    }
}
