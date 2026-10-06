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
                          boolean permissionQuestion, java.util.function.BooleanSupplier current) {
        public Request {
            Objects.requireNonNull(turn); Objects.requireNonNull(current); context = List.copyOf(context); memory = List.copyOf(memory);
            if (context.size() > 128 || memory.size() > 64
                    || context.stream().mapToLong(line -> line.text().length()).sum()
                    + memory.stream().mapToLong(fact -> fact.value().length()).sum() > 32_768)
                throw new IllegalArgumentException("response context bounds");
        }
        public Request(ConversationTurns.Token turn, List<ConversationTurns.Line> context, List<DiscordMemory.Fact> memory) {
            this(turn, context, memory, false);
        }
        public Request(ConversationTurns.Token turn, List<ConversationTurns.Line> context, List<DiscordMemory.Fact> memory, boolean permissionQuestion) {
            this(turn, context, memory, permissionQuestion, () -> true);
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
    private final ThreadPoolExecutor synthesis;
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
        synthesis = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
            Thread thread = new Thread(r, "MinecraftAI-discord-synthesis"); thread.setDaemon(true); return thread;
        });
    }

    public synchronized boolean respond(Request request) {
        return admit(request, null);
    }
    /** Literal code-owned greeting, using the same cancellable voice lane without model inference. */
    synchronized boolean greet(ConversationTurns.Token token, String text) {
        return greet(token, text, null);
    }
    /** Literal text whose follow-up registration runs only after the whole text was heard, still under the current turn. */
    synchronized boolean greet(ConversationTurns.Token token, String text, Runnable afterHeard) {
        if (text == null || text.isBlank() || text.length() > 240) throw new IllegalArgumentException("greeting text");
        return admit(new Request(token, List.of(), List.of()), text, afterHeard);
    }
    private boolean admit(Request request, String greeting) { return admit(request, greeting, null); }
    private boolean admit(Request request, String greeting, Runnable afterHeard) {
        if (closed || !turns.isCurrent(request.turn) || request.turn.equals(admitted)) return false;
        cancelPending();
        worker.purge();
        try { pending = worker.submit(() -> run(request, greeting, afterHeard)); admitted = request.turn; return true; }
        catch (java.util.concurrent.RejectedExecutionException e) { diagnostic.accept("response-capacity"); return false; }
    }

    /** Input adapter calls this on participating speech onset, before waiting for STT. */
    public synchronized void interrupt(String userId) {
        if (turns.speechStarted(userId)) cancelPending();
    }

    /** Caller invalidates the current turn first, then stops inference and queued playback. */
    public synchronized void cancel() { cancelPending(); worker.purge(); }

    private void run(Request request, String greeting, Runnable afterHeard) {
        java.util.concurrent.Future<byte[]> prepared = null;
        try {
            if (!valid(request)) return;
            String response = greeting != null ? greeting : request.permissionQuestion ? "말 편하게 해도 될까요?"
                    : model.respond(new Request(request.turn, request.context, request.memory, false, () -> valid(request)));
            if (!valid(request) || !turns.generated(request.turn, response)) return;
            // Measured only for model answers; fixed code-owned sentences are repeated on purpose. The text of the answer is never reported.
            if (greeting == null && !request.permissionQuestion && AnswerRepetition.repeated(response, request.context, request.turn.userId()))
                diagnostic.accept("dialogue-answer-repeated");
            int completedCharacters = 0;
            var sentences = SentenceChunks.split(response);
            for (int index = 0; index < sentences.size(); index++) {
                String sentence = sentences.get(index);
                if (!valid(request)) return;
                if (sentence.isBlank()) { completedCharacters += sentence.length(); continue; }
                if (prepared == null) prepared = prepare(request, sentence);
                byte[] pcm = prepared.get(); prepared = null;
                if (pcm == null || pcm.length == 0 || pcm.length > 48_000 * 4 * 60 || pcm.length % 4 != 0)
                    throw new IllegalArgumentException("voice PCM bounds");
                if (!valid(request)) return;
                // Exactly one next sentence, synthesized while this sentence is being submitted.
                // All voice calls share one worker; canceled output never reaches playback/history.
                for (int next = index + 1; next < sentences.size(); next++) {
                    if (!sentences.get(next).isBlank()) { prepared = prepare(request, sentences.get(next)); break; }
                }
                int prefix = completedCharacters;
                java.util.concurrent.atomic.AtomicInteger heard = new java.util.concurrent.atomic.AtomicInteger();
                playback.play(request.turn, sentence, pcm, () -> valid(request), characters -> {
                    synchronized (turns) {
                        if (characters >= heard.get() && characters <= sentence.length() && turns.played(request.turn, prefix + characters)) {
                            heard.set(characters);
                            // Admission is atomic with the final frame, not a later worker wakeup.
                            if (greeting != null && prefix + characters == response.length()) turns.greeted(request.turn);
                        }
                    }
                });
                if (!valid(request)) return;
                if (heard.get() != sentence.length()) { turns.finish(request.turn); return; }
                completedCharacters += sentence.length();
            }
            if (valid(request)) {
                if (request.permissionQuestion) turns.askCasualPermission(request.turn, 30_000);
                if (afterHeard != null) afterHeard.run();
                turns.finish(request.turn);
            }
        } catch (InterruptedException e) {
            if (turns.isCurrent(request.turn)) { turns.finish(request.turn); diagnostic.accept("response-provider-interrupted"); }
            Thread.currentThread().interrupt();
        }
        catch (Exception e) { if (valid(request)) { turns.finish(request.turn); diagnostic.accept("response-provider-failed"); } }
        finally { if (prepared != null) prepared.cancel(true); synthesis.purge(); }
    }
    private java.util.concurrent.Future<byte[]> prepare(Request request, String sentence) {
        synthesis.purge();
        return synthesis.submit(() -> {
            if (!valid(request)) throw new java.util.concurrent.CancellationException("retired voice turn");
            byte[] pcm = voice.synthesize(sentence);
            if (!valid(request)) throw new java.util.concurrent.CancellationException("retired voice turn");
            return pcm;
        });
    }
    private boolean valid(Request request) { return !Thread.currentThread().isInterrupted() && turns.isCurrent(request.turn); }
    private void cancelPending() {
        if (pending != null) pending.cancel(true);
        try { playback.stop(); } catch (RuntimeException e) { diagnostic.accept("response-playback-stop-failed"); }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; turns.close();
        try { cancelPending(); } finally { worker.shutdownNow(); synthesis.shutdownNow(); }
    }
}
