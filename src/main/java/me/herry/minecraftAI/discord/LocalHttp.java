package me.herry.minecraftAI.discord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loopback-only provider calls; finite whole-body deadlines and memory, no redirects or proxy. */
public final class LocalHttp implements AutoCloseable {
    public record Response(String mediaType, byte[] body) {
        public Response { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            }).build();
    private final Semaphore capacity = new Semaphore(2);
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    public static URI endpoint(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (RuntimeException e) { throw new IllegalArgumentException("local provider endpoint"); }
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null || !Set.of("127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getPath().isEmpty() || value.length() > 300)
            throw new IllegalArgumentException("provider must use an explicit loopback HTTP endpoint");
        return uri;
    }
    public Response post(URI endpoint, String mediaType, byte[] body, Duration timeout, int maxBytes) throws IOException, InterruptedException {
        endpoint(endpoint.toString());
        if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(120)) > 0 || maxBytes < 1 || maxBytes > 12_000_000
                || body == null || body.length > 2_000_000) throw new IllegalArgumentException("local provider request bounds");
        if (closed.get()) throw new IOException("local provider closed");
        if (!capacity.tryAcquire()) throw new IOException("local provider capacity");
        CompletableFuture<HttpResponse<byte[]>> task = null;
        try {
            if (closed.get()) throw new IOException("local provider closed");
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", mediaType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            task = client.sendAsync(request, info -> new BoundedBody(maxBytes)); pending.add(task);
            if (closed.get()) task.cancel(true);
            HttpResponse<byte[]> response = task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IOException("local provider HTTP status " + response.statusCode());
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
            return new Response(type, response.body());
        } catch (java.util.concurrent.TimeoutException e) { throw new java.net.http.HttpTimeoutException("local provider deadline"); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) { throw new IOException("local provider request failed"); }
        catch (IllegalStateException e) { throw new IOException("local provider closed during request"); }
        finally { if (task != null) { task.cancel(true); pending.remove(task); } capacity.release(); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.forEach(task -> task.cancel(true)); client.shutdownNow();
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription next) { subscription = next; next.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) return;
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) { subscription.cancel(); result.completeExceptionally(new IOException("local provider response bounds")); return; }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(new IOException("local provider body failed")); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
