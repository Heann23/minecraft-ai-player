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
}
