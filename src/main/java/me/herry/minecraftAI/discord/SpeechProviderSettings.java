package me.herry.minecraftAI.discord;

import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

public record SpeechProviderSettings(URI recognitionEndpoint, URI voiceEndpoint, String voice,
                                     Duration recognitionTimeout, Duration voiceTimeout) {
    public SpeechProviderSettings {
        LocalHttp.endpoint(java.util.Objects.requireNonNull(recognitionEndpoint).toString());
        LocalHttp.endpoint(java.util.Objects.requireNonNull(voiceEndpoint).toString());
        if (voice == null || (!voice.isEmpty() && !voice.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))) throw new IllegalArgumentException("providers.tts.voice");
        for (Duration timeout : new Duration[]{recognitionTimeout, voiceTimeout})
            if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(120)) > 0) throw new IllegalArgumentException("speech provider timeout");
    }
    public static SpeechProviderSettings read(Function<String, Object> yaml) {
        return new SpeechProviderSettings(LocalHttp.endpoint(DiscordSettings.string(yaml, "providers.stt.endpoint", "http://127.0.0.1:8080/inference")),
                LocalHttp.endpoint(DiscordSettings.string(yaml, "providers.tts.endpoint", "http://127.0.0.1:5000/synthesize")),
                DiscordSettings.string(yaml, "providers.tts.voice", ""),
                Duration.ofSeconds(DiscordSettings.number(yaml, "providers.stt.timeout-seconds", 15, 1, 120)),
                Duration.ofSeconds(DiscordSettings.number(yaml, "providers.tts.timeout-seconds", 10, 1, 120)));
    }
}
