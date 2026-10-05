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
    public synchronized String describe() {
        var view = view();
        if (view.code() != Code.FRESH) return switch (view.code()) {
            case NOT_CONFIGURED -> "연결할 게임 AI가 아직 지정되지 않았어요. 관리자가 discord.yml의 game.target-ai에 AI 이름을 설정해 주세요.";
            case NOT_FOUND -> "설정한 게임 AI가 현재 서버에 없어요. 지금 게임 상태는 알 수 없어요.";
            default -> "현재 게임 상태를 확인하지 못했어요. 오래된 상태를 현재 상태로 말하지 않을게요.";
        };
        var state = view.snapshot();
        String items = state.items().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).limit(12)
                .map(item -> item.getKey() + " " + item.getValue() + "개").collect(java.util.stream.Collectors.joining(", "));
        if (state.items().size() > 12) items += " · 그 외 " + (state.items().size() - 12) + "종류";
        return state.aiName() + ": " + state.activity() + ". 체력 " + Math.round(state.health() * 10) / 10.0 + ", 허기 " + state.food()
                + ".\n위치: " + state.dimension() + " " + state.x() + ", " + state.y() + ", " + state.z()
                + (state.reason().isEmpty() ? "" : "\n마지막 판단 이유: " + state.reason())
                + "\n현재 인벤토리 종류·개수: " + (items.isEmpty() ? "비어 있어요." : items)
                + "\n서버에서 최근 확인한 상태예요. 아이템 별명·사연이나 과거 경험은 이 자료로 확인할 수 없어요.";
    }
    @Override public synchronized void close() { closed = true; target = ""; latest = null; code = Code.STOPPED; }
}
