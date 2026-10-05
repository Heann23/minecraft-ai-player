package me.herry.minecraftAI.discord;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalHttpTest {
    private static final byte[] INPUT = "한글 입력".getBytes(StandardCharsets.UTF_8);
    private static final Duration DEADLINE = Duration.ofSeconds(2);
    private static URI endpoint(HttpServer server, String path) { return LocalHttp.endpoint("http://127.0.0.1:" + server.getAddress().getPort() + path); }
    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    @Test void onlyExplicitLoopbackAddressesAreAccepted() {
        assertNotNull(LocalHttp.endpoint("http://127.0.0.1:8080/inference"));
        assertNotNull(LocalHttp.endpoint("http://[::1]:5000/synthesize"));
        for (String value : new String[]{"http://example.com:8080/test", "http://127.0.0.1.evil:8080/", "http://localhost:8080/", "file:///temp",
                "http://127.0.0.1/test", "http://127.0.0.1:8080", "http://user@127.0.0.1:8080/", "http://127.0.0.1:8080/?secret=value", "http://127.0.0.1:8080/#x", "http:/missing"})
            assertThrows(IllegalArgumentException.class, () -> LocalHttp.endpoint(value), value);
    }
    @Test void boundedRequestRetainsUtf8AndResponseIsImmutable() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test", exchange -> {
            assertEquals("POST", exchange.getRequestMethod()); assertArrayEquals(INPUT, exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(new byte[]{'{','}'}); exchange.close();
        }); server.start();
        try (var http = new LocalHttp()) {
            var response = http.post(endpoint(server, "/test"), "application/json", INPUT, DEADLINE, 20);
            assertEquals("application/json", response.mediaType()); byte[] first = response.body(); first[0] = 0;
            assertArrayEquals(new byte[]{'{','}'}, response.body());
        } finally { server.stop(0); }
    }
    @Test void redirectIsRejectedWithoutSendingBodyToDestination() throws Exception {
        var calls = new AtomicInteger(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", endpoint(server, "/target").toString()); exchange.sendResponseHeaders(307, -1); exchange.close(); });
        server.createContext("/target", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); }); server.start();
        try (var http = new LocalHttp()) {
            assertThrows(IOException.class, () -> http.post(endpoint(server, "/redirect"), "application/json", INPUT, DEADLINE, 20)); assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }
    @Test void chunkedBodyExceedingLimitIsCancelled() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> { exchange.sendResponseHeaders(200, 0); try { exchange.getResponseBody().write(new byte[4096]); } finally { exchange.close(); } }); server.start();
        try (var http = new LocalHttp()) { assertThrows(IOException.class, () -> http.post(endpoint(server, "/large"), "application/json", INPUT, DEADLINE, 100)); }
        finally { server.stop(0); }
    }
    @Test void deadlineCoversBodyEvenAfterHeadersArrive() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write(1); exchange.getResponseBody().flush(); entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try (var http = new LocalHttp(); var worker = Executors.newSingleThreadExecutor()) {
            // Establish the client's connection before measuring a stalled response body.
            http.post(endpoint(server, "/ready"), "application/json", INPUT, DEADLINE, 100);
            var result = worker.submit(() -> http.post(endpoint(server, "/slow"), "application/json", INPUT, Duration.ofSeconds(1), 100));
            await(entered); var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(java.net.http.HttpTimeoutException.class, error.getCause());
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void closeCancelsActiveCallAndRejectsNewRequests() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wait", exchange -> {
            entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start(); var http = new LocalHttp();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var pending = worker.submit(() -> http.post(endpoint(server, "/wait"), "application/json", INPUT, DEADLINE, 100)); await(entered); http.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> http.post(endpoint(server, "/wait"), "application/json", INPUT, DEADLINE, 100));
        } finally { http.close(); release.countDown(); server.stop(0); }
    }
    @Test void threadInterruptionRetiresCallWithoutWaitingForProvider() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var ended = new CountDownLatch(1); var interruptions = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wait", exchange -> {
            entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try (var http = new LocalHttp()) {
            Thread caller = new Thread(() -> {
                try { http.post(endpoint(server, "/wait"), "application/json", INPUT, DEADLINE, 100); }
                catch (InterruptedException interrupted) { interruptions.incrementAndGet(); }
                catch (IOException error) { fail(error); }
                finally { ended.countDown(); }
            }); caller.start(); await(entered); caller.interrupt(); await(ended); assertEquals(1, interruptions.get());
        } finally { release.countDown(); server.stop(0); }
    }
}
