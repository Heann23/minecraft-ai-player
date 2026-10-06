package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiscordSettingsTest {
    @Test void defaultsDisableNetworkAndConfigureTenMinuteBackups() {
        var settings = DiscordSettings.read(key -> null);
        assertFalse(settings.enabled()); assertTrue(settings.autoConnect()); assertEquals(600_000, settings.backup().intervalMillis());
        assertEquals(24, settings.backup().periodic()); assertEquals(7, settings.backup().daily());
    }
    @Test void idsAreSettingsRatherThanCodeConstants() {
        var values = Map.<String, Object>of("enabled", true, "guild-id", "111111111111111111", "voice-channel-id", "222222222222222222");
        var settings = DiscordSettings.read(values::get);
        assertEquals("111111111111111111", settings.guildId()); assertEquals("222222222222222222", settings.voiceChannelId());
    }
    @Test void enabledRequiresBothIds() { assertThrows(IllegalArgumentException.class, () -> DiscordSettings.read(Map.<String, Object>of("enabled", true)::get)); }
    @Test void numericIdsAndWrongTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DiscordSettings.read(Map.<String, Object>of("guild-id", 111111111111111111L)::get));
        assertThrows(IllegalArgumentException.class, () -> DiscordSettings.read(Map.<String, Object>of("enabled", "false")::get));
        assertThrows(IllegalArgumentException.class, () -> DiscordSettings.read(Map.<String, Object>of("conversation.followup-seconds", 1.5)::get));
    }
    @Test void unsafeBoundsAreRejectedRatherThanSilentlyClamped() {
        assertThrows(IllegalArgumentException.class, () -> DiscordSettings.read(Map.<String, Object>of("backup.interval-seconds", 0)::get));
        assertThrows(IllegalArgumentException.class, () -> DiscordSettings.read(Map.<String, Object>of("conversation.context-lines", 129)::get));
        assertThrows(IllegalArgumentException.class, () -> DiscordSettings.read(Map.<String, Object>of("token-env", "token value")::get));
    }
}
