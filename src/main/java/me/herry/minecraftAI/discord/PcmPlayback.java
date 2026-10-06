package me.herry.minecraftAI.discord;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** In-memory 20ms frame transport seam. Confirmation means submitted playback, not remote audibility. */
public final class PcmPlayback implements ResponsePipeline.Playback, AutoCloseable {
    public record Frame(UUID playbackId, int sequence, byte[] pcm) {
        public Frame { pcm = pcm.clone(); }
        @Override public byte[] pcm() { return pcm.clone(); }
    }
    private static final class Clip {
        final UUID id = UUID.randomUUID();
        final String text;
        final byte[] pcm;
        final BooleanSupplier valid;
        final IntConsumer heard;
        int offset, sequence;
        Frame inFlight;
        boolean complete, finishing;
        Clip(String text, byte[] pcm, BooleanSupplier valid, IntConsumer heard) {
            this.text = text; this.pcm = pcm.clone(); this.valid = valid; this.heard = heard;
        }
    }
    private final Object lock = new Object();
    private Clip clip;
    private boolean closed;

    @Override public void play(ConversationTurns.Token token, String text, byte[] pcm, BooleanSupplier valid, IntConsumer heard) throws Exception {
        if (pcm == null || pcm.length == 0 || pcm.length % 4 != 0 || pcm.length > 48_000 * 4 * 60) throw new IllegalArgumentException("playback PCM bounds");
        if (!valid.getAsBoolean()) return;
        Clip mine = new Clip(text, pcm, valid, heard);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos((long) pcm.length * 1000 / (48_000 * 4) + 3000);
        synchronized (lock) {
            if (closed) return;
            if (clip != null) throw new IllegalStateException("playback already active");
            clip = mine; lock.notifyAll();
            try {
                while (clip == mine && !mine.complete) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new java.util.concurrent.TimeoutException("playback transport timeout");
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                }
            } finally { if (clip == mine) clip = null; lock.notifyAll(); }
        }
    }
    /** Called by the audio transport; no disk, TTS, inference, or waits occur here. */
    public Frame nextFrame() {
        Clip current;
        synchronized (lock) { current = clip; }
        if (current == null) return null;
        if (!current.valid.getAsBoolean()) { cancel(current); return null; }
        synchronized (lock) {
            if (clip != current || current.inFlight != null || current.complete || current.finishing) return null;
            int bytes = Math.min(PcmAudio.FRAME_BYTES, current.pcm.length - current.offset);
            byte[] frame = new byte[PcmAudio.FRAME_BYTES];
            System.arraycopy(current.pcm, current.offset, frame, 0, bytes);
            current.offset += bytes;
            current.inFlight = new Frame(current.id, ++current.sequence, frame);
            return current.inFlight;
        }
    }
    /** Reject duplicates, reordered frames, and frames from a canceled or replaced clip. */
    public boolean submitted(Frame frame) {
        Clip current;
        synchronized (lock) { current = clip; }
        if (current == null || frame == null || !current.valid.getAsBoolean()) { if (current != null) cancel(current); return false; }
        boolean finished;
        synchronized (lock) {
            if (clip != current || current.inFlight == null || !current.id.equals(frame.playbackId) || current.inFlight.sequence != frame.sequence) return false;
            current.inFlight = null; finished = current.offset == current.pcm.length;
            current.finishing = finished;
        }
        if (finished) {
            // Never call external code while holding the playback lock.
            try { if (current.valid.getAsBoolean()) current.heard.accept(current.text.length()); }
            finally { synchronized (lock) { if (clip == current) { current.complete = true; lock.notifyAll(); } } }
        }
        return true;
    }
    public boolean ready() { synchronized (lock) { return clip != null && clip.inFlight == null && !clip.complete && !clip.finishing; } }
    private void cancel(Clip current) { synchronized (lock) { if (clip == current) { clip = null; lock.notifyAll(); } } }
    @Override public void stop() { synchronized (lock) { clip = null; lock.notifyAll(); } }
    @Override public void close() { synchronized (lock) { closed = true; clip = null; lock.notifyAll(); } }
}
