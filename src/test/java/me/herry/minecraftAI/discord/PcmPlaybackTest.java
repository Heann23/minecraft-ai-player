package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PcmPlaybackTest {
    private ConversationTurns.Token token() { return new ConversationTurns.Token(java.util.UUID.randomUUID(), 1, 1, "A"); }
    private CompletableFuture<Void> play(PcmPlayback playback, byte[] pcm, AtomicBoolean valid, AtomicInteger heard) {
        return CompletableFuture.runAsync(() -> {
            try { playback.play(token(), "문장", pcm, valid::get, heard::set); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private PcmPlayback.Frame next(PcmPlayback playback) throws Exception {
        return org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            while (true) { PcmPlayback.Frame frame = playback.nextFrame(); if (frame != null) return frame; Thread.sleep(1); }
        });
    }
    @Test void framesHaveExactSizeAndTextIsConfirmedOnlyAfterLastSubmission() throws Exception {
        try (var playback = new PcmPlayback()) {
            var valid = new AtomicBoolean(true); var heard = new AtomicInteger(); byte[] pcm = new byte[3844]; java.util.Arrays.fill(pcm, (byte) 7);
            var task = play(playback, pcm, valid, heard); var first = next(playback);
            assertEquals(PcmAudio.FRAME_BYTES, first.pcm().length); assertNull(playback.nextFrame()); assertTrue(playback.submitted(first)); assertEquals(0, heard.get());
            var second = next(playback); assertEquals(7, second.pcm()[3]); assertEquals(0, second.pcm()[4]); assertTrue(playback.submitted(second)); task.get(3, TimeUnit.SECONDS); assertEquals(2, heard.get());
            assertFalse(playback.submitted(first)); assertNull(playback.nextFrame());
        }
    }
    @Test void cancellationDropsQueuedAndInFlightFramesAndRejectsTheirConfirmations() throws Exception {
        try (var playback = new PcmPlayback()) {
            var valid = new AtomicBoolean(true); var heard = new AtomicInteger(); var task = play(playback, new byte[7680], valid, heard); var frame = next(playback);
            valid.set(false); playback.stop(); task.get(3, TimeUnit.SECONDS); assertFalse(playback.submitted(frame)); assertNull(playback.nextFrame()); assertEquals(0, heard.get());
        }
    }
    @Test void replacedClipRejectsOldFrameEvenWhenSequenceMatches() throws Exception {
        try (var playback = new PcmPlayback()) {
            var old = play(playback, new byte[3840], new AtomicBoolean(true), new AtomicInteger()); var frame = next(playback); playback.stop(); old.get(3, TimeUnit.SECONDS);
            var heard = new AtomicInteger(); var fresh = play(playback, new byte[3840], new AtomicBoolean(true), heard); var next = next(playback);
            assertFalse(playback.submitted(frame)); assertTrue(playback.submitted(next)); fresh.get(3, TimeUnit.SECONDS); assertEquals(2, heard.get());
        }
    }
    @Test void packetArrayCannotMutateThePendingFrame() throws Exception {
        try (var playback = new PcmPlayback()) {
            var task = play(playback, new byte[3840], new AtomicBoolean(true), new AtomicInteger()); var frame = next(playback); byte[] array = frame.pcm(); array[0] = 1;
            assertEquals(0, frame.pcm()[0]); playback.submitted(frame); task.get(3, TimeUnit.SECONDS);
        }
    }
    @Test void closeWakesWaitingPlaybackAndPreventsLaterFrames() throws Exception {
        var playback = new PcmPlayback(); var task = play(playback, new byte[3840], new AtomicBoolean(true), new AtomicInteger()); next(playback);
        playback.close(); task.get(3, TimeUnit.SECONDS); assertNull(playback.nextFrame()); assertFalse(playback.ready());
    }
    @Test void sentenceChunksPreserveExactTextAndUnicodeBoundaries() {
        String response = "안녕하세요.  첫 문장이에요!\n두 번째 문장이에요?";
        var chunks = SentenceChunks.split(response); assertEquals(response, String.join("", chunks)); assertEquals(3, chunks.size());
        String longText = "가".repeat(239) + "😀" + "나".repeat(300);
        var longChunks = SentenceChunks.split(longText); assertEquals(longText, String.join("", longChunks));
        for (String chunk : longChunks) { assertTrue(chunk.length() <= 240); assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))); }
    }
}
