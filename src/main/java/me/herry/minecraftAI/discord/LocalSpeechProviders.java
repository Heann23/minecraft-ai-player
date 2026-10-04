package me.herry.minecraftAI.discord;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** whisper.cpp multipart WAV and Piper HTTP WAV adapters; servers and models are owned externally. */
public final class LocalSpeechProviders implements SpeechRecognitionWorker.Recognizer, ResponsePipeline.Voice, AutoCloseable {
    private final SpeechProviderSettings settings;
    private final LocalHttp http;
    public LocalSpeechProviders(SpeechProviderSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings); http = new LocalHttp();
    }
    @Override public SpeechRecognitionWorker.Recognition recognize(byte[] pcm, String language) throws IOException, InterruptedException {
        if (!"ko".equals(language)) throw new IllegalArgumentException("Korean recognition only");
        byte[] wave = WaveAudio.mono16k(pcm); String boundary = "MinecraftAI-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "language", "ko"); field(body, boundary, "response_format", "json");
        field(body, boundary, "temperature", "0.0"); field(body, boundary, "temperature_inc", "0.0");
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"utterance.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
        body.writeBytes(wave); write(body, "\r\n--" + boundary + "--\r\n");
        var response = http.post(settings.recognitionEndpoint(), "multipart/form-data; boundary=" + boundary, body.toByteArray(), settings.recognitionTimeout(), 65_536);
        if (!response.mediaType().equals("application/json")) throw new IOException("local recognition response type");
        String text = ProviderJson.string(ProviderJson.read(response.body()), "text").strip();
        if (text.length() > 2000) throw new IOException("local recognition text bounds");
        // An HTTP final transcription is not calibrated recognition confidence.
        // Persistent names and consent need a separate confirmation path; never promote this output blindly.
        return new SpeechRecognitionWorker.Recognition(text, false);
    }
    @Override public byte[] synthesize(String text) throws IOException, InterruptedException {
        if (text == null || text.isBlank() || text.length() > 240) throw new IllegalArgumentException("speech sentence bounds");
        JsonObject body = new JsonObject(); body.addProperty("text", text);
        if (!settings.voice().isEmpty()) body.addProperty("voice", settings.voice());
        var response = http.post(settings.voiceEndpoint(), "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8), settings.voiceTimeout(), 12_000_000);
        if (!Set.of("audio/wav", "audio/x-wav", "audio/wave").contains(response.mediaType())) throw new IOException("local synthesis response type");
        return WaveAudio.discordPcm(response.body());
    }
    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void write(ByteArrayOutputStream out, String value) { out.writeBytes(value.getBytes(StandardCharsets.UTF_8)); }
    @Override public void close() { http.close(); }
}
