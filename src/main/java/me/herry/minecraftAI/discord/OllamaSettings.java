package me.herry.minecraftAI.discord;

import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

/** Local dialogue model configuration; no downloads or cloud fallback. */
public record OllamaSettings(URI endpoint, String model, Duration timeout, int contextTokens, int outputTokens) {
    public OllamaSettings {
        LocalHttp.endpoint(java.util.Objects.requireNonNull(endpoint).toString());
        if (model == null || (!model.isEmpty() && !model.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,99}"))) throw new IllegalArgumentException("providers.llm.model");
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(120)) > 0
                || contextTokens < 512 || contextTokens > 8192 || outputTokens < 32 || outputTokens > 512) throw new IllegalArgumentException("dialogue model bounds");
    }
    public static OllamaSettings read(Function<String, Object> yaml) {
        return new OllamaSettings(LocalHttp.endpoint(DiscordSettings.string(yaml, "providers.llm.endpoint", "http://127.0.0.1:11434/api/chat")),
                DiscordSettings.string(yaml, "providers.llm.model", ""),
                Duration.ofSeconds(DiscordSettings.number(yaml, "providers.llm.timeout-seconds", 15, 1, 120)),
                (int) DiscordSettings.number(yaml, "providers.llm.context-tokens", 4096, 512, 8192),
                (int) DiscordSettings.number(yaml, "providers.llm.output-tokens", 160, 32, 512));
    }
}
