package me.herry.minecraftAI.discord;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** File I/O and pure YAML parsing only; the runtime loads this outside the server thread. */
public record DiscordConfiguration(DiscordSettings discord, OllamaSettings dialogue, SpeechProviderSettings speech,
                                   double minimumRms, long endSilenceMillis, boolean greetOnJoin, String targetAi, String token) {
    public DiscordConfiguration {
        token = token == null ? "" : token;
        java.util.Objects.requireNonNull(discord); java.util.Objects.requireNonNull(dialogue); java.util.Objects.requireNonNull(speech);
        if (!Double.isFinite(minimumRms) || minimumRms <= 0 || minimumRms > 1) throw new IllegalArgumentException("audio.minimum-rms");
        if (endSilenceMillis < 100 || endSilenceMillis > 2500) throw new IllegalArgumentException("audio.end-silence-millis");
        DiscordGameState.target(targetAi);
    }
    /** The bot token must never reach a log or an error message, so the generated text hides it. */
    @Override public String toString() { return "DiscordConfiguration[token=" + (token.isEmpty() ? "from environment" : "in discord.yml") + "]"; }
    public VoiceIngress.Policy capturePolicy() {
        return new VoiceIngress.Policy(8, 3, 2, 5, 1500, endSilenceMillis, endSilenceMillis + 500);
    }
    /** An invalid value in one discord.yml section. Still an IllegalArgumentException; only the fixed code names the section. */
    static final class Invalid extends IllegalArgumentException {
        private final DiscordStartupFailure.Reason reason;
        Invalid(DiscordStartupFailure.Reason reason, Throwable cause) { super(reason.code, cause); this.reason = reason; }
        String diagnostic() { return reason.code; }
    }
    private static <T> T section(DiscordStartupFailure.Reason reason, Supplier<T> read) {
        try { return read.get(); }
        catch (Invalid known) { throw known; }
        catch (IllegalArgumentException invalid) { throw new Invalid(reason, invalid); }
    }
    /** DiscordSettings mixes the connection, conversation and backup keys; each failure message starts with its key. */
    private static DiscordStartupFailure.Reason settingsSection(String message) {
        if (message != null && message.startsWith("conversation")) return DiscordStartupFailure.Reason.CONFIG_CONVERSATION;
        if (message != null && message.startsWith("backup")) return DiscordStartupFailure.Reason.CONFIG_BACKUP;
        return DiscordStartupFailure.Reason.CONFIG_CONNECTION;
    }
    public static DiscordConfiguration read(Function<String, Object> values) {
        Object value = values.apply("audio.minimum-rms");
        if (value != null && !(value instanceof Number)) throw new Invalid(DiscordStartupFailure.Reason.CONFIG_AUDIO, new IllegalArgumentException("audio.minimum-rms must be numeric"));
        DiscordSettings discord;
        try { discord = DiscordSettings.read(values); }
        catch (IllegalArgumentException invalid) { throw new Invalid(settingsSection(invalid.getMessage()), invalid); }
        var dialogue = section(DiscordStartupFailure.Reason.CONFIG_LLM, () -> OllamaSettings.read(values));
        var speech = section(DiscordStartupFailure.Reason.CONFIG_SPEECH, () -> SpeechProviderSettings.read(values));
        long silence = section(DiscordStartupFailure.Reason.CONFIG_AUDIO, () -> DiscordSettings.number(values, "audio.end-silence-millis", 1000, 100, 2500));
        boolean greet = section(DiscordStartupFailure.Reason.CONFIG_CONVERSATION, () -> DiscordSettings.bool(values, "conversation.greet-on-join", true));
        String target = section(DiscordStartupFailure.Reason.CONFIG_GAME, () -> DiscordSettings.string(values, "game.target-ai", ""));
        // Written straight into discord.yml by the owner; empty means the environment variable named by token-env is used instead.
        String token = section(DiscordStartupFailure.Reason.CONFIG_CONNECTION, () -> {
            String written = DiscordSettings.string(values, "token", "").strip();
            if (!written.isEmpty() && !written.matches("[A-Za-z0-9._-]{30,200}")) throw new IllegalArgumentException("token");
            return written;
        });
        try { return new DiscordConfiguration(discord, dialogue, speech, value == null ? 0.01 : ((Number) value).doubleValue(), silence, greet, target, token); }
        catch (IllegalArgumentException invalid) {
            String message = invalid.getMessage();
            throw new Invalid(message != null && message.startsWith("audio.") ? DiscordStartupFailure.Reason.CONFIG_AUDIO : DiscordStartupFailure.Reason.CONFIG_GAME, invalid);
        }
    }
    /** Any file problem is reported by a fixed code only: its text may hold paths. */
    public static DiscordConfiguration load(Path directory, Supplier<InputStream> defaults) throws IOException {
        try { return loadFile(directory, defaults); }
        catch (DiscordStartupFailure known) { throw known; }
        catch (IOException unreadable) { throw new DiscordStartupFailure(DiscordStartupFailure.Reason.CONFIG_UNREADABLE); }
    }
    private static DiscordConfiguration loadFile(Path directory, Supplier<InputStream> defaults) throws IOException {
        Files.createDirectories(directory); Path file = directory.resolve("discord.yml");
        if (!Files.exists(file)) {
            try (InputStream input = defaults.get()) {
                if (input == null) throw new IOException("Discord default configuration missing");
                Files.copy(input, file);
            } catch (java.nio.file.FileAlreadyExistsException concurrentCreator) {
                if (!Files.isRegularFile(file)) throw new IOException("Discord configuration is not a file");
            }
        }
        if (!Files.isRegularFile(file)) throw new IOException("Discord configuration is not a file");
        if (Files.size(file) > 65_536) throw new DiscordStartupFailure(DiscordStartupFailure.Reason.CONFIG_SIZE);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) { bytes = input.readNBytes(65_537); }
        if (bytes.length > 65_536) throw new DiscordStartupFailure(DiscordStartupFailure.Reason.CONFIG_SIZE);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            yaml.loadFromString(text);
        }
        catch (InvalidConfigurationException invalid) { throw new DiscordStartupFailure(DiscordStartupFailure.Reason.CONFIG_SYNTAX); }
        return read(yaml::get);
    }
}
