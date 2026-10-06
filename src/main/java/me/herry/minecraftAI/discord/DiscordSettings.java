package me.herry.minecraftAI.discord;

import java.util.function.Function;

/** Separate discord.yml contract. Parsing never reads tokens or changes game config. */
public record DiscordSettings(boolean enabled, String tokenEnvironment, String guildId, String voiceChannelId,
                              String characterId, boolean autoConnect, long followupMillis, int contextLines,
                              DiscordMemoryStore.BackupPolicy backup) {
    public DiscordSettings {
        if (tokenEnvironment == null || !tokenEnvironment.matches("[A-Z][A-Z0-9_]{0,99}")) throw new IllegalArgumentException("token-env");
        snowflake(guildId, enabled, "guild-id"); snowflake(voiceChannelId, enabled, "voice-channel-id");
        if (characterId == null || !characterId.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("character-id");
        if (followupMillis < 1000 || followupMillis > 600_000 || contextLines < 1 || contextLines > 128) throw new IllegalArgumentException("conversation limits");
        java.util.Objects.requireNonNull(backup);
    }
    /** Use yaml::get at the runtime boundary; unknown/wrong types fail before startup. */
    public static DiscordSettings read(Function<String, Object> values) {
        return new DiscordSettings(bool(values, "enabled", false), string(values, "token-env", "MINECRAFTAI_DISCORD_TOKEN"),
                string(values, "guild-id", ""), string(values, "voice-channel-id", ""), string(values, "character-id", "herry"),
                bool(values, "auto-connect", true), number(values, "conversation.followup-seconds", 60, 1, 600) * 1000,
                (int) number(values, "conversation.context-lines", 32, 1, 128),
                new DiscordMemoryStore.BackupPolicy(bool(values, "backup.enabled", true),
                        number(values, "backup.interval-seconds", 600, 1, 86_400) * 1000,
                        (int) number(values, "backup.periodic-retention", 24, 1, 1000),
                        (int) number(values, "backup.daily-retention", 7, 1, 365),
                        number(values, "backup.max-megabytes", 128, 16, 1024) * 1024 * 1024));
    }
    static String string(Function<String, Object> values, String key, String fallback) {
        Object value = values.apply(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a quoted string");
        return text;
    }
    static boolean bool(Function<String, Object> values, String key, boolean fallback) {
        Object value = values.apply(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(key + " must be boolean");
        return flag;
    }
    static long number(Function<String, Object> values, String key, long fallback, long min, long max) {
        Object value = values.apply(key);
        if (value == null) return fallback;
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException(key + " must be integer");
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw new IllegalArgumentException(key + " out of range");
        return number;
    }
    private static void snowflake(String value, boolean required, String label) {
        if (value == null || (!value.isEmpty() && !value.matches("[1-9][0-9]{16,19}")) || (required && value.isEmpty()))
            throw new IllegalArgumentException(label + " must be a quoted Discord ID");
    }
}
