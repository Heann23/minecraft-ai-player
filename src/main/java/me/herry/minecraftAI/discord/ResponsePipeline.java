package me.herry.minecraftAI.discord;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Single inference lane. Providers must honor interruption and bounded request deadlines. */
public final class ResponsePipeline implements AutoCloseable {
    public record Request(ConversationTurns.Token turn, List<ConversationTurns.Line> context, List<DiscordMemory.Fact> memory,
                          boolean permissionQuestion) {
        public Request {
            Objects.requireNonNull(turn); context = List.copyOf(context); memory = List.copyOf(memory);
            if (context.size() > 128 || memory.size() > 64
                    || context.stream().mapToLong(line -> line.text().length()).sum()
                    + memory.stream().mapToLong(fact -> fact.value().length()).sum() > 32_768)
                throw new IllegalArgumentException("response context bounds");
        }
        public Request(ConversationTurns.Token turn, List<ConversationTurns.Line> context, List<DiscordMemory.Fact> memory) {
            this(turn, context, memory, false);
        }
    }
    public interface Model { String respond(Request request) throws Exception; }
    public interface Voice {
        /** Return 48kHz stereo signed 16-bit PCM, not Opus; transport owns packetization. */
        byte[] synthesize(String text) throws Exception;
    }
    public interface Playback {
        /** Must check valid before each frame and report the text prefix actually heard. */
        void play(ConversationTurns.Token token, String text, byte[] pcm,
                  java.util.function.BooleanSupplier valid, java.util.function.IntConsumer heard) throws Exception;
        void stop();
    }
    private final ConversationTurns turns;
    private final Model model;
    private final Voice voice;
    private final Playback playback;
    private final Consumer<String> diagnostic;
    private final ThreadPoolExecutor worker;
    private Future<?> pending;
    private ConversationTurns.Token admitted;
    private boolean closed;

    public ResponsePipeline(ConversationTurns turns, Model model, Voice voice, Playback playback, Consumer<String> diagnostic) {
        this.turns = Objects.requireNonNull(turns); this.model = Objects.requireNonNull(model);
        this.voice = Objects.requireNonNull(voice); this.playback = Objects.requireNonNull(playback);
        this.diagnostic = Objects.requireNonNull(diagnostic);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
            Thread thread = new Thread(r, "MinecraftAI-discord-response"); thread.setDaemon(true); return thread;
        });
    }

    public synchronized boolean respond(Request request) {
        if (closed || !turns.isCurrent(request.turn) || request.turn.equals(admitted)) return false;
        cancelPending();
        worker.purge();
        try { pending = worker.submit(() -> run(request)); admitted = request.turn; return true; }
        catch (java.util.concurrent.RejectedExecutionException e) { diagnostic.accept("response-capacity"); return false; }
    }

    /** Input adapter calls this on participating speech onset, before waiting for STT. */
    public synchronized void interrupt(String userId) {
        if (turns.speechStarted(userId)) cancelPending();
    }

    private void run(Request request) {
        try {
            if (!valid(request)) return;
            String response = request.permissionQuestion ? "말 편하게 해도 될까요?" : model.respond(request);
            if (!valid(request) || !turns.generated(request.turn, response)) return;
            int completedCharacters = 0;
            for (String sentence : SentenceChunks.split(response)) {
                if (!valid(request)) return;
                if (sentence.isBlank()) { completedCharacters += sentence.length(); continue; }
                byte[] pcm = voice.synthesize(sentence);
                if (pcm == null || pcm.length == 0 || pcm.length > 48_000 * 4 * 60 || pcm.length % 4 != 0)
                    throw new IllegalArgumentException("voice PCM bounds");
                if (!valid(request)) return;
                int prefix = completedCharacters;
                java.util.concurrent.atomic.AtomicInteger heard = new java.util.concurrent.atomic.AtomicInteger();
                playback.play(request.turn, sentence, pcm, () -> valid(request), characters -> {
                    if (characters >= heard.get() && characters <= sentence.length() && turns.played(request.turn, prefix + characters)) heard.set(characters);
                });
                if (!valid(request)) return;
                if (heard.get() != sentence.length()) { turns.finish(request.turn); return; }
                completedCharacters += sentence.length();
            }
            if (valid(request)) {
                if (request.permissionQuestion) turns.askCasualPermission(request.turn, 30_000);
                turns.finish(request.turn);
            }
        } catch (InterruptedException e) {
            if (turns.isCurrent(request.turn)) { turns.finish(request.turn); diagnostic.accept("response-provider-interrupted"); }
            Thread.currentThread().interrupt();
        }
        catch (Exception e) { if (valid(request)) { turns.finish(request.turn); diagnostic.accept("response-provider-failed"); } }
    }
    private boolean valid(Request request) { return !Thread.currentThread().isInterrupted() && turns.isCurrent(request.turn); }
    private void cancelPending() {
        if (pending != null) pending.cancel(true);
        try { playback.stop(); } catch (RuntimeException e) { diagnostic.accept("response-playback-stop-failed"); }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; turns.close();
        try { cancelPending(); } finally { worker.shutdownNow(); }
    }
}
