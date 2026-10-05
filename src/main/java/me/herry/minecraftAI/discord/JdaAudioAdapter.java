package me.herry.minecraftAI.discord;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.UserAudio;

/** User PCM only. Never combine speakers or listen to bots, including our own output. */
public final class JdaAudioAdapter implements AudioReceiveHandler, AudioSendHandler, AutoCloseable {
    private final DiscordSession session;
    private final double minimumRms;
    private final Consumer<String> diagnostic;
    private volatile Set<String> users = Set.of();
    private boolean connected, closed;
    private long membership;
    private PcmPlayback.Frame pending;
    public JdaAudioAdapter(DiscordSession session, double minimumRms, Consumer<String> diagnostic) {
        this.session = java.util.Objects.requireNonNull(session); this.diagnostic = java.util.Objects.requireNonNull(diagnostic);
        if (!Double.isFinite(minimumRms) || minimumRms <= 0 || minimumRms > 1) throw new IllegalArgumentException("audio.minimum-rms");
        this.minimumRms = minimumRms;
    }
    public synchronized void connected(boolean value) {
        if (closed) return;
        connected = value; pending = null; membership++;
        if (!value) { users = Set.of(); observe(session.quiet(true)); observe(session.participants(Set.of(), Map.of())); }
        else observe(session.quiet(false));
    }
    public synchronized CompletableFuture<Void> participants(Set<String> ids, Map<String, String> aliases) {
        if (closed || !connected) return CompletableFuture.completedFuture(null);
        Set<String> next = Set.copyOf(ids); users = Set.of(); long generation = ++membership;
        CompletableFuture<Void> accepted = session.participants(next, aliases);
        accepted.whenComplete((ignored, failure) -> {
            synchronized (JdaAudioAdapter.this) { if (!closed && connected && membership == generation && failure == null) users = next; }
        });
        return accepted;
    }
    @Override public synchronized boolean canReceiveUser() { return !closed && connected; }
    @Override public void handleUserAudio(UserAudio audio) {
        if (audio.getUser().isBot()) return;
        receive(audio.getUser().getId(), audio.getAudioData(1.0));
    }
    /** Exposed packet boundary for tests; speaker identity still comes only from JDA's user packet. */
    void receive(String user, byte[] pcm) {
        synchronized (this) { if (closed || !connected || !users.contains(user)) return; }
        if (pcm == null || pcm.length != PcmAudio.FRAME_BYTES) { diagnostic.accept("discord-audio-format"); return; }
        observe(session.audio(user, pcm, PcmAudio.rms(pcm) >= minimumRms, false));
    }
    @Override public synchronized boolean canProvide() {
        if (closed || !connected) return false;
        if (pending == null) pending = session.nextFrame();
        return pending != null;
    }
    @Override public synchronized ByteBuffer provide20MsAudio() {
        if (closed || !connected || pending == null) return null;
        PcmPlayback.Frame frame = pending; pending = null;
        // JDA has requested this frame. This is handoff confirmation, not remote audibility.
        if (!session.submitted(frame)) return null;
        return ByteBuffer.wrap(frame.pcm());
    }
    @Override public boolean isOpus() { return false; }
    private void observe(CompletableFuture<Void> result) { result.whenComplete((ignored, failed) -> { if (failed != null) diagnostic.accept("discord-audio-event-rejected"); }); }
    @Override public synchronized void close() { closed = true; connected = false; users = Set.of(); pending = null; }
}
