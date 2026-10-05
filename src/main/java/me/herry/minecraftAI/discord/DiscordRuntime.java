package me.herry.minecraftAI.discord;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/** Startup and resource cleanup never wait on Bukkit's server thread. No secrets in status or diagnostics. */
public final class DiscordRuntime implements AutoCloseable {
    public enum State { STARTING, DISABLED, RUNNING, FAILED, STOPPED }
    public interface Connection extends AutoCloseable {
        /** Immediately prevent more audio or commands; must not wait for network or disk. */
        void stop();
        @Override void close();
    }
    @FunctionalInterface public interface Loader { DiscordConfiguration load() throws Exception; }
    @FunctionalInterface public interface Factory { Connection open(DiscordConfiguration settings, String token) throws Exception; }
    private final Object gate = new Object();
    private final ExecutorService worker;
    private final Consumer<String> diagnostic;
    private final CompletableFuture<State> started = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private Future<?> starting;
    private Connection connection;
    private volatile State state = State.STARTING;
    private final java.util.concurrent.atomic.AtomicLong diagnosticFailures = new java.util.concurrent.atomic.AtomicLong();
    private boolean closed;

    public DiscordRuntime(Loader loader, Function<String, String> environment, Factory factory, Consumer<String> diagnostic) {
        java.util.Objects.requireNonNull(loader); java.util.Objects.requireNonNull(environment); java.util.Objects.requireNonNull(factory);
        this.diagnostic = java.util.Objects.requireNonNull(diagnostic);
        worker = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "MinecraftAI-discord-lifecycle"); thread.setDaemon(true); return thread; });
        starting = worker.submit(() -> start(loader, environment, factory));
    }
    private void start(Loader loader, Function<String, String> environment, Factory factory) {
        Connection created = null;
        try {
            DiscordConfiguration configuration = loader.load();
            if (!configuration.discord().enabled()) { publish(State.DISABLED); return; }
            if (isClosed()) return;
            String token = environment.apply(configuration.discord().tokenEnvironment());
            if (token == null || token.isBlank()) { report("discord-token-missing"); publish(State.FAILED); return; }
            created = factory.open(configuration, token);
            java.util.Objects.requireNonNull(created);
            synchronized (gate) {
                if (!closed) { connection = created; created = null; state = State.RUNNING; }
            }
            if (created == null) started.complete(State.RUNNING);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); if (!isClosed()) { report("discord-start-interrupted"); publish(State.FAILED); } }
        catch (Exception | LinkageError failed) { if (!isClosed()) {
            report(failed instanceof DiscordStartupFailure known ? known.diagnostic() : "discord-start-failed");
            publish(State.FAILED);
        } }
        finally { if (created != null) dispose(created); }
    }
    private boolean isClosed() { synchronized (gate) { return closed; } }
    private void publish(State value) { synchronized (gate) { if (!closed) { state = value; started.complete(value); } } }
    private void report(String code) { try { diagnostic.accept(code); } catch (RuntimeException sinkFailure) { diagnosticFailures.incrementAndGet(); } }
    private void dispose(Connection resource) {
        try { resource.stop(); } catch (RuntimeException failed) { report("discord-stop-failed"); }
        try { resource.close(); } catch (RuntimeException failed) { report("discord-cleanup-failed"); }
    }
    public State state() { return state; }
    public long diagnosticFailures() { return diagnosticFailures.get(); }
    public CompletableFuture<State> started() { return started.copy(); }
    public CompletableFuture<Void> stopped() { return stopped.copy(); }
    @Override public void close() {
        Connection resource;
        synchronized (gate) {
            if (closed) return;
            closed = true; state = State.STOPPED; resource = connection; connection = null;
            started.complete(State.STOPPED); starting.cancel(true);
        }
        if (resource != null) { try { resource.stop(); } catch (RuntimeException failed) { report("discord-stop-failed"); } }
        worker.execute(() -> { try { if (resource != null) dispose(resource); } finally { stopped.complete(null); } });
        worker.shutdown();
    }
}
