package me.herry.minecraftAI.discord;

import club.minnced.discord.jdave.ffi.LibDave;
import club.minnced.opus.util.OpusLibrary;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Runs in a fresh JVM with the distribution JAR and Paper's SLF4J API only, not the test runtime. */
public final class DiscordJarSmoke {
    private DiscordJarSmoke() {}
    public static void main(String[] args) throws Exception {
        if (LibDave.getMaxSupportedProtocolVersion() <= 0 || !OpusLibrary.loadFromJar() || !OpusLibrary.isInitialized())
            throw new IllegalStateException("Packaged voice natives failed");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/version", exchange -> {
            byte[] response = "{\"version\":\"0.35.1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            try { exchange.getResponseBody().write(response); } finally { exchange.close(); }
        });
        server.createContext("/api/show", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"model_info\":{\"architecture\":\"test-local\"},\"capabilities\":[\"completion\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            try { exchange.getResponseBody().write(response); } finally { exchange.close(); }
        });
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"반가워요.\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            try { exchange.getResponseBody().write(response); } finally { exchange.close(); }
        }); server.start();
        try (var model = new OllamaDialogue(new OllamaSettings(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat"),
                "test-local", Duration.ofSeconds(2), 1024, 64), () -> 1000)) {
            var token = new ConversationTurns.Token(UUID.randomUUID(), 1, 1, "user");
            if (!model.respond(new ResponsePipeline.Request(token, List.of(), List.of())).equals("반가워요."))
                throw new IllegalStateException("Packaged dialogue failed");
        } finally { server.stop(0); }
        Class.forName("net.dv8tion.jda.api.JDABuilder");
        System.out.println("Discord distribution: local dialogue, DAVE and Opus passed");
    }
}
