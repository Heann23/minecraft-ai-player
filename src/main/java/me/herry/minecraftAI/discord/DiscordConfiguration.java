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
                                   double minimumRms, long endSilenceMillis, boolean greetOnJoin) {
    public DiscordConfiguration {
        java.util.Objects.requireNonNull(discord); java.util.Objects.requireNonNull(dialogue); java.util.Objects.requireNonNull(speech);
        if (!Double.isFinite(minimumRms) || minimumRms <= 0 || minimumRms > 1) throw new IllegalArgumentException("audio.minimum-rms");
        if (endSilenceMillis < 100 || endSilenceMillis > 2500) throw new IllegalArgumentException("audio.end-silence-millis");
    }
    public VoiceIngress.Policy capturePolicy() {
        return new VoiceIngress.Policy(8, 3, 2, 5, 1500, endSilenceMillis, endSilenceMillis + 500);
    }
    public static DiscordConfiguration read(Function<String, Object> values) {
        Object value = values.apply("audio.minimum-rms");
        if (value != null && !(value instanceof Number)) throw new IllegalArgumentException("audio.minimum-rms must be numeric");
        return new DiscordConfiguration(DiscordSettings.read(values), OllamaSettings.read(values), SpeechProviderSettings.read(values),
                value == null ? 0.01 : ((Number) value).doubleValue(),
                DiscordSettings.number(values, "audio.end-silence-millis", 1000, 100, 2500),
                DiscordSettings.bool(values, "conversation.greet-on-join", true));
    }
    public static DiscordConfiguration load(Path directory, Supplier<InputStream> defaults) throws IOException {
        Files.createDirectories(directory); Path file = directory.resolve("discord.yml");
        if (!Files.exists(file)) {
            try (InputStream input = defaults.get()) {
                if (input == null) throw new IOException("Discord default configuration missing");
                Files.copy(input, file);
            } catch (java.nio.file.FileAlreadyExistsException concurrentCreator) {
                if (!Files.isRegularFile(file)) throw new IOException("Discord configuration is not a file");
            }
        }
        if (!Files.isRegularFile(file) || Files.size(file) > 65_536) throw new IOException("Discord configuration size");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) { bytes = input.readNBytes(65_537); }
        if (bytes.length > 65_536) throw new IOException("Discord configuration size");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            yaml.loadFromString(text);
        }
        catch (InvalidConfigurationException invalid) { throw new IOException("Discord configuration syntax"); }
        return read(yaml::get);
    }
}
