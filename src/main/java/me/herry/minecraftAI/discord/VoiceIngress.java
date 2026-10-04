package me.herry.minecraftAI.discord;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded, per-speaker capture. The VAD decision is supplied by an independent detector. */
public final class VoiceIngress implements AutoCloseable {
    public record Policy(int maxUsers, int preRollFrames, int onsetFrames, int minSpeechFrames,
                         int maxFrames, long normalPauseMillis, long continuationPauseMillis) {
        public Policy {
            if (maxUsers < 1 || maxUsers > 16 || preRollFrames < 0 || preRollFrames > 10
                    || onsetFrames < 1 || onsetFrames > 10 || minSpeechFrames < onsetFrames || minSpeechFrames > 50
                    || maxFrames < minSpeechFrames + preRollFrames || maxFrames > 1500
                    || normalPauseMillis < 100 || continuationPauseMillis < normalPauseMillis || continuationPauseMillis > 3000)
                throw new IllegalArgumentException("voice capture policy");
        }
        public static Policy defaults() { return new Policy(8, 3, 2, 5, 1500, 500, 1000); }
    }
    public record Route(UUID session, long generation, String userId, long utterance) {
        public Route {
            if (session == null || generation < 1 || utterance < 1 || userId == null || userId.isBlank() || userId.length() > 100)
                throw new IllegalArgumentException("voice route");
        }
    }
    public record Utterance(Route route, byte[] pcm, long endedAt, boolean durationLimited) {
        public Utterance {
            java.util.Objects.requireNonNull(route);
            if (pcm == null || pcm.length == 0 || pcm.length % PcmAudio.FRAME_BYTES != 0 || pcm.length > PcmAudio.FRAME_BYTES * 1500 || endedAt < 0)
                throw new IllegalArgumentException("voice recording");
            pcm = pcm.clone();
        }
        @Override public byte[] pcm() { return pcm.clone(); }
    }
    public record Onset(Route route) {}
    public record Events(List<Onset> onsets, List<Utterance> completed) {
        public Events { onsets = List.copyOf(onsets); completed = List.copyOf(completed); }
        private static Events empty() { return new Events(List.of(), List.of()); }
    }
    private static final class Capture {
        final ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
        ByteArrayOutputStream audio;
        Route route;
        int consecutiveSpeech, speechFrames, frames;
        long lastSpeech, lastPacket;
        boolean continuation;
    }
    private final UUID session = UUID.randomUUID();
    private final Policy policy;
    private final LongSupplier clock;
    private final Map<String, Capture> speakers = new HashMap<>();
    private final Map<String, Long> latest = new HashMap<>();
    private Set<String> members = Set.of();
    private long generation = 1, utterances;
    private boolean closed;

    public VoiceIngress(Policy policy, LongSupplier clock) { this.policy = java.util.Objects.requireNonNull(policy); this.clock = java.util.Objects.requireNonNull(clock); }
    public synchronized void participants(Set<String> humanUserIds) {
        if (closed) return;
        Set<String> next = Set.copyOf(humanUserIds);
        if (next.size() > policy.maxUsers || next.stream().anyMatch(id -> id.isBlank() || id.length() > 100)) throw new IllegalArgumentException("voice participants");
        if (!next.equals(members)) {
            generation++; speakers.clear(); latest.clear(); members = next;
        }
    }
    /** No STT/network callbacks run here. Input PCM is copied before returning to JDA. */
    public synchronized Events frame(String userId, byte[] pcm, boolean speech, boolean continuationExpected) {
        if (closed || !members.contains(userId)) return Events.empty();
        PcmAudio.requireFrame(pcm);
        long now = clock.getAsLong();
        Capture capture = speakers.computeIfAbsent(userId, ignored -> new Capture());
        List<Utterance> completed = new ArrayList<>(); List<Onset> onsets = new ArrayList<>();
        // A fresh packet arriving after a long gap cannot silently join an old utterance.
        if (capture.audio != null && now - capture.lastSpeech >= pause(capture)) finish(capture, now, false, completed);
        if (capture.audio == null && now - capture.lastPacket > policy.continuationPauseMillis) {
            capture.preRoll.clear(); capture.consecutiveSpeech = 0;
        }
        capture.lastPacket = now;
        if (speech) { capture.lastSpeech = now; capture.continuation = continuationExpected; capture.consecutiveSpeech++; }
        else capture.consecutiveSpeech = 0;
        if (capture.audio == null) {
            capture.preRoll.addLast(pcm.clone());
            while (capture.preRoll.size() > policy.preRollFrames + policy.onsetFrames) capture.preRoll.removeFirst();
            if (capture.consecutiveSpeech < policy.onsetFrames) return new Events(onsets, completed);
            capture.route = new Route(session, generation, userId, ++utterances);
            latest.put(userId, utterances);
            capture.audio = new ByteArrayOutputStream(); capture.frames = 0; capture.speechFrames = capture.consecutiveSpeech;
            for (byte[] frame : capture.preRoll) { capture.audio.writeBytes(frame); capture.frames++; }
            capture.preRoll.clear(); onsets.add(new Onset(capture.route));
        } else {
            capture.audio.writeBytes(pcm); capture.frames++;
            if (speech) capture.speechFrames++;
        }
        if (capture.frames >= policy.maxFrames) finish(capture, now, true, completed);
        return new Events(onsets, completed);
    }
    /** Call independently of packet callbacks: Discord may send no packets during silence. */
    public synchronized Events tick() {
        if (closed) return Events.empty();
        long now = clock.getAsLong(); List<Utterance> completed = new ArrayList<>();
        for (Capture capture : speakers.values()) {
            if (capture.audio != null && now - capture.lastSpeech >= pause(capture)) finish(capture, now, false, completed);
        }
        return new Events(List.of(), completed);
    }
    public synchronized boolean valid(Route route) {
        return !closed && route != null && route.session.equals(session) && route.generation == generation
                && members.contains(route.userId) && latest.getOrDefault(route.userId, -1L) == route.utterance;
    }
    public synchronized void reset() { generation++; speakers.clear(); latest.clear(); }
    @Override public synchronized void close() { closed = true; generation++; speakers.clear(); latest.clear(); members = Set.of(); }
    private long pause(Capture capture) { return capture.continuation ? policy.continuationPauseMillis : policy.normalPauseMillis; }
    private void finish(Capture capture, long now, boolean limited, List<Utterance> completed) {
        if (capture.speechFrames >= policy.minSpeechFrames) completed.add(new Utterance(capture.route, capture.audio.toByteArray(), now, limited));
        capture.audio = null; capture.route = null; capture.frames = 0; capture.speechFrames = 0;
        capture.consecutiveSpeech = 0; capture.preRoll.clear();
    }
}
