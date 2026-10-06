package me.herry.minecraftAI.discord;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordRuntimeTest {
    private DiscordConfiguration settings(boolean enabled) {
        var values = Map.<String, Object>of("enabled", enabled, "guild-id", "12345678901234567", "voice-channel-id", "12345678901234568");
        return DiscordConfiguration.read(values::get);
    }
    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    @Test void disabledConfigurationDoesNotReadSecretOrOpenProviders() throws Exception {
        try (var runtime = new DiscordRuntime(() -> settings(false), key -> { fail("disabled token read"); return null; },
                (settings, token) -> { fail("disabled connection"); return null; }, code -> fail("disabled diagnostic"))) {
            assertEquals(DiscordRuntime.State.DISABLED, runtime.started().get(3, TimeUnit.SECONDS));
        }
    }
    @Test void absentTokenDoesNotOpenConnectionAndDiagnosticContainsNoValue() throws Exception {
        List<String> codes = new CopyOnWriteArrayList<>();
        try (var runtime = new DiscordRuntime(() -> settings(true), key -> null,
                (settings, token) -> { fail("missing token connection"); return null; }, codes::add)) {
            assertEquals(DiscordRuntime.State.FAILED, runtime.started().get(3, TimeUnit.SECONDS));
            assertEquals(List.of("discord-token-missing"), codes);
        }
    }
    @Test void failureTextAndTokenAreNeverCopiedToDiagnostics() throws Exception {
        List<String> codes = new CopyOnWriteArrayList<>();
        try (var runtime = new DiscordRuntime(() -> settings(true), key -> "secret-token",
                (settings, token) -> { assertEquals("secret-token", token); throw new IllegalStateException("private conversation and secret-token"); }, codes::add)) {
            assertEquals(DiscordRuntime.State.FAILED, runtime.started().get(3, TimeUnit.SECONDS)); assertEquals(List.of("discord-start-failed"), codes);
        }
    }
    @Test void knownStartupFailuresExposeOnlyFixedDiagnosticCodes() throws Exception {
        for (var reason : DiscordStartupFailure.Reason.values()) {
            List<String> codes = new CopyOnWriteArrayList<>();
            try (var runtime = new DiscordRuntime(() -> settings(true), key -> "secret-token",
                    (settings, token) -> { throw new DiscordStartupFailure(reason); }, codes::add)) {
                assertEquals(DiscordRuntime.State.FAILED, runtime.started().get(3, TimeUnit.SECONDS));
                assertEquals(List.of(reason.code), codes);
                assertFalse(codes.getFirst().contains("secret-token"));
            }
        }
    }
    @Test void closeDuringConfigurationCancelsStartupBeforeOpeningConnection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var runtime = new DiscordRuntime(() -> { entered.countDown(); ignoringInterrupt(release); return settings(true); }, key -> "token",
                (settings, token) -> { calls.incrementAndGet(); return null; }, code -> {});
        try { await(entered); runtime.close(); assertEquals(DiscordRuntime.State.STOPPED, runtime.state()); release.countDown(); runtime.stopped().get(3, TimeUnit.SECONDS); assertEquals(0, calls.get()); }
        finally { release.countDown(); runtime.close(); }
    }
    @Test void resourceCreatedAfterCloseIsDisposedWithoutReturningToRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var disposed = new AtomicInteger();
        var runtime = new DiscordRuntime(() -> settings(true), key -> "token", (settings, token) -> {
            entered.countDown(); ignoringInterrupt(release);
            return new DiscordRuntime.Connection() { public void stop() {} public void close() { disposed.incrementAndGet(); } };
        }, code -> {});
        try { await(entered); runtime.close(); release.countDown(); runtime.stopped().get(3, TimeUnit.SECONDS);
            assertEquals(1, disposed.get()); assertEquals(DiscordRuntime.State.STOPPED, runtime.state()); assertEquals(DiscordRuntime.State.STOPPED, runtime.started().get()); }
        finally { release.countDown(); runtime.close(); }
    }
    @Test void immediateStopRunsOnCallerWhileBlockingCleanupRunsOnWorker() throws Exception {
        CountDownLatch cleanup = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> cleanupThread = new AtomicReference<>(), stopThread = new AtomicReference<>();
        var runtime = new DiscordRuntime(() -> settings(true), key -> "token", (settings, token) -> new DiscordRuntime.Connection() {
            public void stop() { stopThread.compareAndSet(null, Thread.currentThread()); }
            public void close() { cleanupThread.set(Thread.currentThread()); cleanup.countDown(); ignoringInterrupt(release); }
        }, code -> {});
        try { assertEquals(DiscordRuntime.State.RUNNING, runtime.started().get(3, TimeUnit.SECONDS)); runtime.close(); await(cleanup);
            assertSame(Thread.currentThread(), stopThread.get()); assertNotSame(Thread.currentThread(), cleanupThread.get()); assertFalse(runtime.stopped().isDone());
            release.countDown(); runtime.stopped().get(3, TimeUnit.SECONDS); runtime.close(); }
        finally { release.countDown(); runtime.close(); }
    }
    @Test void brokenDiagnosticsDoNotHideFailureOrPreventShutdown() throws Exception {
        var runtime = new DiscordRuntime(() -> { throw new IllegalArgumentException("private config"); }, key -> "token",
                (settings, token) -> null, code -> { throw new IllegalStateException("sink failure"); });
        assertEquals(DiscordRuntime.State.FAILED, runtime.started().get(3, TimeUnit.SECONDS)); assertEquals(1, runtime.diagnosticFailures());
        runtime.close(); runtime.stopped().get(3, TimeUnit.SECONDS);
    }
    @Test void normalDisableDoesNotWaitForBlockingCleanupButFinalServerStopCanAwaitIt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var runtime = new DiscordRuntime(() -> settings(true), key -> "token", (settings, token) -> new DiscordRuntime.Connection() {
            public void stop() {}
            public void close() { entered.countDown(); ignoringInterrupt(release); }
        }, code -> fail(code));
        try {
            runtime.started().get(3, TimeUnit.SECONDS); runtime.close(); await(entered);
            assertTimeout(java.time.Duration.ofSeconds(1), () -> assertFalse(runtime.awaitServerShutdown(false, java.time.Duration.ofSeconds(6))));
            release.countDown(); assertTrue(runtime.awaitServerShutdown(true, java.time.Duration.ofSeconds(3)));
        } finally { release.countDown(); runtime.close(); }
    }
    @Test void finalStopHasFiniteDeadlineAndReportsOnlyCodeEvenIfProviderIgnoresInterrupts() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var codes = new CopyOnWriteArrayList<String>();
        var runtime = new DiscordRuntime(() -> settings(true), key -> "token", (settings, token) -> new DiscordRuntime.Connection() {
            public void stop() {}
            public void close() { entered.countDown(); ignoringInterrupt(release); }
        }, codes::add);
        try {
            runtime.started().get(3, TimeUnit.SECONDS); runtime.close(); await(entered);
            assertTimeout(java.time.Duration.ofSeconds(1), () -> assertFalse(runtime.awaitServerShutdown(true, java.time.Duration.ofMillis(20))));
            assertEquals(List.of("discord-shutdown-deadline"), codes);
        } finally { release.countDown(); runtime.close(); runtime.stopped().get(3, TimeUnit.SECONDS); }
    }
    @Test void finalShutdownRequiresCloseAndRejectsIndefiniteOrUnboundedWait() throws Exception {
        try (var runtime = new DiscordRuntime(() -> settings(false), key -> null, (settings, token) -> null, ignored -> {})) {
            runtime.started().get(3, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> runtime.awaitServerShutdown(true, java.time.Duration.ofSeconds(1)));
            for (var timeout : List.of(java.time.Duration.ZERO, java.time.Duration.ofNanos(1), java.time.Duration.ofSeconds(7)))
                assertThrows(IllegalArgumentException.class, () -> runtime.awaitServerShutdown(true, timeout));
            assertThrows(IllegalArgumentException.class, () -> runtime.awaitServerShutdown(true, null));
        }
    }
    private static void ignoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) { try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
