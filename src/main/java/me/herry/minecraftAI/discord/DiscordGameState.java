package me.herry.minecraftAI.discord;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import me.herry.minecraftAI.ai.comm.GameStateSnapshot;

/** Main-thread sampling and immutable off-thread access. An explicit mapping never guesses which AI is Herry. */
public final class DiscordGameState implements AutoCloseable {
    public enum Code { NOT_CONFIGURED, NOT_FOUND, UNAVAILABLE, FRESH, STALE, STOPPED }
    public record View(Code code, GameStateSnapshot snapshot) {
        public View {
            java.util.Objects.requireNonNull(code);
            if ((code == Code.FRESH) != (snapshot != null)) throw new IllegalArgumentException("game state visibility");
        }
    }
    private final BooleanSupplier mainThread;
    private final Function<String, GameStateSnapshot> reader;
    private final LongSupplier monotonic;
    private final Consumer<String> diagnostic;
    private String target = "";
    private GameStateSnapshot latest;
    private long sampledAt;
    private Code code = Code.NOT_CONFIGURED;
    private boolean closed;
    private boolean failureReported;
    private long diagnosticFailures;
    public DiscordGameState(BooleanSupplier mainThread, Function<String, GameStateSnapshot> reader, LongSupplier monotonic, Consumer<String> diagnostic) {
        this.mainThread = java.util.Objects.requireNonNull(mainThread); this.reader = java.util.Objects.requireNonNull(reader);
        this.monotonic = java.util.Objects.requireNonNull(monotonic); this.diagnostic = java.util.Objects.requireNonNull(diagnostic);
    }
    public static String target(String value) {
        if (value == null || (!value.isEmpty() && !value.matches("[A-Za-z0-9_]{3,16}"))) throw new IllegalArgumentException("game.target-ai");
        return value;
    }
    public synchronized void configure(String value) {
        target(value);
        if (closed) return;
        target = value; latest = null; failureReported = false; code = value.isEmpty() ? Code.NOT_CONFIGURED : Code.UNAVAILABLE;
    }
    /** Scheduled once per second by Paper. No network, inference, or disk work is allowed in the reader. */
    public synchronized void refresh() {
        if (!mainThread.getAsBoolean()) throw new IllegalStateException("game state sampling requires main thread");
        if (closed || target.isEmpty()) return;
        try {
            var snapshot = reader.apply(target);
            if (snapshot != null && !snapshot.aiName().equalsIgnoreCase(target)) throw new IllegalStateException("game target changed");
            latest = snapshot; sampledAt = monotonic.getAsLong(); code = snapshot == null ? Code.NOT_FOUND : Code.FRESH; failureReported = false;
        } catch (RuntimeException failed) {
            latest = null; code = Code.UNAVAILABLE;
            if (!failureReported) {
                failureReported = true;
                try { diagnostic.accept("discord-game-state-unavailable"); } catch (RuntimeException sinkFailure) { diagnosticFailures++; }
            }
        }
    }
    public synchronized View view() {
        long age = monotonic.getAsLong() - sampledAt;
        if (code == Code.FRESH && (age < 0 || age > 2500)) return new View(Code.STALE, null);
        return new View(code, latest);
    }
    public synchronized long diagnosticFailures() { return diagnosticFailures; }
    @Override public synchronized void close() { closed = true; target = ""; latest = null; code = Code.STOPPED; }
}
