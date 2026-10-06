package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static me.herry.minecraftAI.discord.ConversationTurns.*;

class ResponsePipelineTest {
    private ConversationTurns turns() { var turns = new ConversationTurns(System::currentTimeMillis, 60_000, 8); turns.join("A"); return turns; }
    private Token call(ConversationTurns turns, String id) { return turns.accept("A", id, "해리 안녕", Address.CHARACTER, false).token(); }
    private ResponsePipeline.Request request(ConversationTurns turns, Token token) { return new ResponsePipeline.Request(token, turns.context(), List.of()); }
    private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    private static final class Player implements ResponsePipeline.Playback {
        final CountDownLatch done = new CountDownLatch(1); final AtomicInteger plays = new AtomicInteger();
        @Override public void play(Token token, String text, byte[] pcm, java.util.function.BooleanSupplier valid, java.util.function.IntConsumer heard) {
            if (valid.getAsBoolean()) { plays.incrementAndGet(); heard.accept(text.length()); } done.countDown();
        }
        @Override public void stop() { }
    }
    private static final String HEARD = "오늘은 나무를 모으러 갈게요.";
    /** A conversation in which the user already heard HEARD, ready for the next turn. */
    private Token afterHeardAnswer(ConversationTurns turns) {
        var first = call(turns, "1"); turns.generated(first, HEARD); turns.played(first, HEARD.length()); turns.finish(first);
        return call(turns, "2");
    }
    @Test void anAnswerRepeatingARecentHeardOneIsCountedButStillSpoken() throws Exception {
        var turns = turns(); Token token = afterHeardAnswer(turns);
        var codes = new java.util.concurrent.CopyOnWriteArrayList<String>(); var player = new Player();
        try (var pipeline = new ResponsePipeline(turns, request -> "오늘은 나무를 모으러 갈게요!", text -> new byte[3840], player, codes::add)) {
            assertTrue(pipeline.respond(request(turns, token))); await(player.done);
        }
        assertEquals(1, player.plays.get()); assertEquals(List.of("dialogue-answer-repeated"), codes);
    }
    @Test void aDifferentAnswerIsNotCounted() throws Exception {
        var turns = turns(); Token token = afterHeardAnswer(turns);
        var codes = new java.util.concurrent.CopyOnWriteArrayList<String>(); var player = new Player();
        try (var pipeline = new ResponsePipeline(turns, request -> "철은 곡괭이를 만든 다음에 구울게요.", text -> new byte[3840], player, codes::add)) {
            assertTrue(pipeline.respond(request(turns, token))); await(player.done);
        }
        assertEquals(1, player.plays.get()); assertEquals(List.of(), codes);
    }
    @Test void fixedCodeOwnedTextIsNeverCountedAsRepetition() throws Exception {
        var turns = turns(); Token token = afterHeardAnswer(turns);
        var codes = new java.util.concurrent.CopyOnWriteArrayList<String>(); var player = new Player();
        try (var pipeline = new ResponsePipeline(turns, request -> "쓰이지 않는 모델 응답이에요", text -> new byte[3840], player, codes::add)) {
            assertTrue(pipeline.greet(token, HEARD)); await(player.done);
        }
        assertEquals(1, player.plays.get()); assertEquals(List.of(), codes);
    }
    @Test void fullFakeRoundTripPlaysAnswer() throws Exception {
        var turns = turns(); Token token = call(turns, "1"); var player = new Player();
        try (var pipeline = new ResponsePipeline(turns, request -> "안녕하세요.", text -> new byte[3840], player, code -> fail(code))) {
            assertTrue(pipeline.respond(request(turns, token))); await(player.done);
        }
        assertEquals(1, player.plays.get());
    }
    @Test void nextSentenceIsReadyDuringPlaybackWithoutConcurrentSynthesis() throws Exception {
        var turns = turns(); var token = call(turns, "lookahead");
        var nextReady = new CountDownLatch(1); var finished = new CountDownLatch(1);
        var active = new AtomicInteger(); var maximum = new AtomicInteger(); var plays = new AtomicInteger();
        var playback = new ResponsePipeline.Playback() {
            public void stop() {}
            public void play(Token turn, String text, byte[] pcm, java.util.function.BooleanSupplier valid, java.util.function.IntConsumer heard) throws Exception {
                if (plays.incrementAndGet() == 1) await(nextReady);
                assertTrue(valid.getAsBoolean()); heard.accept(text.length());
                if (plays.get() == 2) finished.countDown();
            }
        };
        try (var pipeline = new ResponsePipeline(turns, request -> "첫 문장이에요. 다음 문장이에요.", text -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { if (text.contains("다음")) nextReady.countDown(); return new byte[3840]; }
            finally { active.decrementAndGet(); }
        }, playback, code -> fail(code))) {
            assertTrue(pipeline.respond(request(turns, token))); await(finished);
            assertEquals(2, plays.get()); assertEquals(1, maximum.get());
        }
    }
    @Test void canceledLookaheadCannotBecomePlayedOrHeardContext() throws Exception {
        var turns = turns(); var token = call(turns, "cancel-lookahead");
        var nextEntered = new CountDownLatch(1); var nextEnded = new CountDownLatch(1);
        var playbackEntered = new CountDownLatch(1); var playbackEnded = new CountDownLatch(1); var plays = new AtomicInteger();
        var playback = new ResponsePipeline.Playback() {
            public void stop() {}
            public void play(Token turn, String text, byte[] pcm, java.util.function.BooleanSupplier valid, java.util.function.IntConsumer heard) throws Exception {
                try {
                    plays.incrementAndGet(); playbackEntered.countDown(); await(nextEntered);
                    new CountDownLatch(1).await();
                } finally { playbackEnded.countDown(); }
            }
        };
        try (var pipeline = new ResponsePipeline(turns, request -> "첫 문장이에요. 취소할 문장이에요.", text -> {
            if (text.contains("취소")) { nextEntered.countDown(); try { new CountDownLatch(1).await(); } finally { nextEnded.countDown(); } }
            return new byte[3840];
        }, playback, code -> fail(code))) {
            pipeline.respond(request(turns, token)); await(nextEntered); await(playbackEntered); pipeline.interrupt("A");
            await(nextEnded); await(playbackEnded); assertEquals(1, plays.get());
            assertTrue(turns.context().stream().noneMatch(Line::assistant));
        }
    }
    @Test void lateModelThatIgnoresCancellationCannotPlayAndOnlyOneInferenceRuns() throws Exception {
        var turns = turns(); Token old = call(turns, "1"); var player = new Player();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var active = new AtomicInteger(); var max = new AtomicInteger();
        ResponsePipeline.Model model = request -> {
            int running = active.incrementAndGet(); max.accumulateAndGet(running, Math::max);
            try {
                if (request.turn().equals(old)) { entered.countDown(); boolean finished = false; while (!finished) { try { release.await(); finished = true; } catch (InterruptedException ignored) { } } return "취소된 답변"; }
                return "새 답변";
            } finally { active.decrementAndGet(); }
        };
        try (var pipeline = new ResponsePipeline(turns, model, text -> new byte[3840], player, code -> fail(code))) {
            pipeline.respond(request(turns, old)); await(entered); pipeline.interrupt("A"); Token fresh = call(turns, "2");
            assertTrue(pipeline.respond(request(turns, fresh))); release.countDown(); await(player.done);
            assertEquals(1, player.plays.get()); assertEquals(1, max.get());
        } finally { release.countDown(); }
    }
    @Test void cancellationDuringTtsPreventsPlayback() throws Exception {
        var turns = turns(); Token token = call(turns, "1"); var player = new Player();
        var synthesis = new CountDownLatch(1); var ended = new CountDownLatch(1);
        try (var pipeline = new ResponsePipeline(turns, request -> "답변", text -> {
            synthesis.countDown(); try { new CountDownLatch(1).await(); return new byte[3840]; } finally { ended.countDown(); }
        }, player, code -> fail(code))) {
            pipeline.respond(request(turns, token)); await(synthesis); pipeline.interrupt("A"); await(ended); assertEquals(0, player.plays.get());
        }
    }
    @Test void closeRejectsLateCompletionAndNewRequests() throws Exception {
        var turns = turns(); Token token = call(turns, "1"); var player = new Player(); var entered = new CountDownLatch(1); var ended = new CountDownLatch(1);
        var pipeline = new ResponsePipeline(turns, request -> {
            entered.countDown(); try { new CountDownLatch(1).await(); return "답변"; } finally { ended.countDown(); }
        }, text -> new byte[3840], player, code -> fail(code));
        try { pipeline.respond(request(turns, token)); await(entered); pipeline.close(); await(ended); assertEquals(0, player.plays.get()); assertFalse(pipeline.respond(request(turns, token))); }
        finally { pipeline.close(); }
    }
    @Test void providerFailureProducesContentFreeDiagnosticAndNoPlayback() throws Exception {
        var turns = turns(); Token token = call(turns, "1"); var player = new Player(); var diagnosed = new CountDownLatch(1);
        try (var pipeline = new ResponsePipeline(turns, request -> { throw new Exception("private text"); }, text -> new byte[3840], player, code -> {
            assertEquals("response-provider-failed", code); diagnosed.countDown();
        })) {
            pipeline.respond(request(turns, token)); await(diagnosed); assertEquals(0, player.plays.get()); assertFalse(turns.isCurrent(token));
        }
    }
    @Test void providerInterruptionFinishesItsTurnAndReportsNoContent() throws Exception {
        var turns = turns(); Token token = call(turns, "1"); var player = new Player(); var diagnosed = new CountDownLatch(1);
        try (var pipeline = new ResponsePipeline(turns, request -> { throw new InterruptedException("private text"); }, text -> new byte[3840], player, code -> {
            assertEquals("response-provider-interrupted", code); diagnosed.countDown();
        })) {
            pipeline.respond(request(turns, token)); await(diagnosed); assertFalse(turns.isCurrent(token)); assertEquals(0, player.plays.get());
        }
    }
    @Test void failingPlaybackStopCannotPreventWorkerShutdown() throws Exception {
        var turns = turns(); Token token = call(turns, "1"); var entered = new CountDownLatch(1); var ended = new CountDownLatch(1);
        var stopFailures = new AtomicInteger();
        var playback = new ResponsePipeline.Playback() {
            @Override public void stop() { throw new IllegalStateException("private text"); }
            @Override public void play(Token token, String text, byte[] pcm, java.util.function.BooleanSupplier valid, java.util.function.IntConsumer heard) { fail("unexpected playback"); }
        };
        var pipeline = new ResponsePipeline(turns, request -> {
            entered.countDown(); try { new CountDownLatch(1).await(); return "answer"; } finally { ended.countDown(); }
        }, text -> new byte[3840], playback, code -> { assertEquals("response-playback-stop-failed", code); stopFailures.incrementAndGet(); });
        try { assertTrue(pipeline.respond(request(turns, token))); await(entered); assertDoesNotThrow(pipeline::close); await(ended); assertEquals(2, stopFailures.get()); }
        finally { pipeline.close(); }
    }
    @Test void sentenceSynthesisStaysOrderedAndFailedLaterSentenceIsNotRemembered() throws Exception {
        var turns = turns(); Token token = call(turns, "1"); var player = new Player(); var diagnosed = new CountDownLatch(1);
        var synthesized = new java.util.ArrayList<String>(); var captured = new java.util.concurrent.atomic.AtomicReference<List<Line>>();
        try (var pipeline = new ResponsePipeline(turns, request -> "첫 문장. 둘째 문장.", text -> {
            synthesized.add(text); if (synthesized.size() == 2) throw new Exception("TTS private details"); return new byte[3840];
        }, player, code -> { assertEquals("response-provider-failed", code); captured.set(turns.context()); diagnosed.countDown(); })) {
            assertTrue(pipeline.respond(request(turns, token))); await(diagnosed);
            assertEquals(List.of("첫 문장. ", "둘째 문장."), synthesized); assertEquals(1, player.plays.get());
            assertTrue(captured.get().stream().anyMatch(line -> line.assistant() && line.text().equals("첫 문장. ")));
            assertFalse(captured.get().stream().anyMatch(line -> line.text().contains("둘째")));
        }
    }
    @Test void duplicateResponseRequestDoesNotStartAnotherInference() throws Exception {
        var turns = turns(); Token token = call(turns, "1"); var player = new Player(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pipeline = new ResponsePipeline(turns, request -> { entered.countDown(); release.await(); return "답변"; }, text -> new byte[3840], player, code -> fail(code))) {
            assertTrue(pipeline.respond(request(turns, token))); await(entered); assertFalse(pipeline.respond(request(turns, token))); release.countDown(); await(player.done);
        } finally { release.countDown(); }
    }
    @Test void leadingWhitespaceDoesNotAttemptEmptySpeechSynthesis() throws Exception {
        var turns = turns(); Token token = call(turns, "1"); var player = new Player(); var synthesized = new java.util.ArrayList<String>();
        try (var pipeline = new ResponsePipeline(turns, request -> "\n안녕하세요.", text -> {
            assertFalse(text.isBlank()); synthesized.add(text); return new byte[3840];
        }, player, code -> fail(code))) {
            pipeline.respond(request(turns, token)); await(player.done); assertEquals(List.of("안녕하세요."), synthesized);
        }
    }
}
