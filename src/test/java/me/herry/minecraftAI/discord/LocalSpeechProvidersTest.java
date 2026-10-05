package me.herry.minecraftAI.discord;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalSpeechProvidersTest {
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final LocalSpeechProviders providers;
        final AtomicReference<byte[]> request = new AtomicReference<>();
        final AtomicReference<String> requestType = new AtomicReference<>();
        final AtomicReference<String> method = new AtomicReference<>();
        final AtomicReference<String> type = new AtomicReference<>("application/json; charset=utf-8");
        final AtomicReference<byte[]> output = new AtomicReference<>("{\"text\":\" 해리야 내 이름은 민수야 \"}".getBytes(StandardCharsets.UTF_8));
        Fixture(String voice) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                request.set(exchange.getRequestBody().readAllBytes());
                requestType.set(exchange.getRequestHeaders().getFirst("Content-Type")); method.set(exchange.getRequestMethod());
                byte[] bytes = output.get(); exchange.getResponseHeaders().set("Content-Type", type.get());
                exchange.sendResponseHeaders(200, bytes.length);
                try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
            }); server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            providers = new LocalSpeechProviders(new SpeechProviderSettings(URI.create(base + "/inference"),
                    URI.create(base + "/synthesize"), voice, Duration.ofSeconds(2), Duration.ofSeconds(2)));
        }
        void json(String text) { output.set(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() { providers.close(); server.stop(0); }
    }
    @Test void whisperUsesKoreanMultipartWaveWithoutInventingRecognitionConfidence() throws Exception {
        try (var fixture = new Fixture("")) {
            byte[] pcm = new byte[]{0x34, 0x12, (byte) 0xcc, (byte) 0xed};
            var recognized = fixture.providers.recognize(pcm, "ko");
            assertEquals("해리야 내 이름은 민수야", recognized.text()); assertFalse(recognized.reliableFinal());
            assertEquals("POST", fixture.method.get());
            String boundary = fixture.requestType.get().split("boundary=")[1];
            String multipart = new String(fixture.request.get(), StandardCharsets.ISO_8859_1);
            for (String field : new String[]{"language\"\r\n\r\nko", "response_format\"\r\n\r\njson",
                    "temperature\"\r\n\r\n0.0", "temperature_inc\"\r\n\r\n0.0"}) assertTrue(multipart.contains(field));
            assertTrue(multipart.endsWith("\r\n--" + boundary + "--\r\n"));
            int waveStart = multipart.indexOf("RIFF"); assertTrue(waveStart > 0);
            assertArrayEquals(WaveAudio.mono16k(pcm), Arrays.copyOfRange(fixture.request.get(), waveStart, waveStart + 48));
        }
    }
    @Test void piperGetsUtf8SentenceAndSelectedVoiceThenProducesDiscordSamples() throws Exception {
        try (var fixture = new Fixture("ko_KR-kss-medium")) {
            fixture.type.set("audio/wav"); fixture.output.set(WaveAudio.mono16k(new byte[]{0x34, 0x12, 0x34, 0x12}));
            byte[] pcm = fixture.providers.synthesize("반가워요. \"해리\"예요.");
            var body = JsonParser.parseString(new String(fixture.request.get(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("반가워요. \"해리\"예요.", body.get("text").getAsString());
            assertEquals("ko_KR-kss-medium", body.get("voice").getAsString());
            assertEquals(24, pcm.length);
            for (int offset = 0; offset < pcm.length; offset += 4) assertArrayEquals(new byte[]{0x12, 0x34, 0x12, 0x34}, Arrays.copyOfRange(pcm, offset, offset + 4));
        }
    }
    @Test void defaultVoiceIsOmittedAndMalformedWaveIsNeverPlayed() throws Exception {
        try (var fixture = new Fixture("")) {
            fixture.type.set("audio/x-wav"); fixture.output.set(WaveAudio.mono16k(new byte[]{0, 0}));
            fixture.providers.synthesize("안녕하세요.");
            assertFalse(JsonParser.parseString(new String(fixture.request.get(), StandardCharsets.UTF_8)).getAsJsonObject().has("voice"));
            fixture.output.set(new byte[]{0, 1}); assertThrows(IOException.class, () -> fixture.providers.synthesize("안녕하세요."));
            fixture.type.set("audio/mpeg"); assertThrows(IOException.class, () -> fixture.providers.synthesize("안녕하세요."));
        }
    }
    @Test void malformedAmbiguousOrExcessiveRecognitionDoesNotBecomeAnUtterance() throws Exception {
        try (var fixture = new Fixture("")) {
            for (String json : new String[]{"{}", "{\"text\":3}", "{\"text\":null}", "{\"text\":\"private\",\"text\":\"other\"}",
                    "{\"text\":\"private\"} {}", "{\"text\":\"" + "가".repeat(2001) + "\"}"}) {
                fixture.json(json); var error = assertThrows(IOException.class, () -> fixture.providers.recognize(new byte[]{0, 0}, "ko"));
                assertFalse(error.getMessage().contains("private"));
            }
            fixture.output.set(new byte[]{(byte) 0xff}); assertThrows(IOException.class, () -> fixture.providers.recognize(new byte[]{0, 0}, "ko"));
            fixture.type.set("text/html"); assertThrows(IOException.class, () -> fixture.providers.recognize(new byte[]{0, 0}, "ko"));
        }
    }
    @Test void invalidInputAndClosedProvidersDoNotSendNetworkRequests() throws Exception {
        try (var fixture = new Fixture("")) {
            assertThrows(IllegalArgumentException.class, () -> fixture.providers.recognize(new byte[]{0, 0}, "en"));
            assertThrows(IllegalArgumentException.class, () -> fixture.providers.recognize(new byte[]{0}, "ko"));
            assertThrows(IllegalArgumentException.class, () -> fixture.providers.synthesize(" "));
            assertThrows(IllegalArgumentException.class, () -> fixture.providers.synthesize("가".repeat(241)));
            assertNull(fixture.request.get()); fixture.providers.close();
            assertThrows(IOException.class, () -> fixture.providers.recognize(new byte[]{0, 0}, "ko"));
            assertThrows(IOException.class, () -> fixture.providers.synthesize("안녕하세요."));
        }
    }
    @Test void piperRawFlaskBytesRequireValidPcmWaveRegardlessOfHtmlLabel() throws Exception {
        try (var fixture = new Fixture("")) {
            for (String media : new String[]{"text/html; charset=utf-8", "application/octet-stream"}) {
                fixture.type.set(media); fixture.output.set(WaveAudio.mono16k(new byte[]{0x34, 0x12}));
                assertEquals(12, fixture.providers.synthesize("안녕하세요.").length);
                fixture.output.set("<html>private error</html>".getBytes(StandardCharsets.UTF_8));
                assertThrows(IOException.class, () -> fixture.providers.synthesize("안녕하세요."));
            }
        }
    }
    @Test void settingsRejectRemoteEndpointsWrongTypesAndUnboundedTimeouts() {
        var defaults = SpeechProviderSettings.read(key -> null); assertTrue(defaults.voice().isEmpty());
        assertEquals("/inference", defaults.recognitionEndpoint().getPath()); assertEquals("/synthesize", defaults.voiceEndpoint().getPath());
        for (var entry : Map.<String,Object>of("providers.stt.endpoint", "http://example.com:8080/inference",
                "providers.tts.endpoint", 5, "providers.tts.voice", "../../voice", "providers.stt.timeout-seconds", 0,
                "providers.tts.timeout-seconds", 121).entrySet())
            assertThrows(IllegalArgumentException.class, () -> SpeechProviderSettings.read(key -> key.equals(entry.getKey()) ? entry.getValue() : null));
        assertThrows(IllegalArgumentException.class, () -> SpeechProviderSettings.read(key -> key.equals("providers.tts.voice") ? "bad voice" : null));
    }
}
