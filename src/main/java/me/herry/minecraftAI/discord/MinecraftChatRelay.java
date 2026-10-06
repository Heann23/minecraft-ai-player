package me.herry.minecraftAI.discord;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import me.herry.minecraftAI.ai.comm.CommunicationHub;
import me.herry.minecraftAI.ai.comm.IncomingMessage;
import me.herry.minecraftAI.ai.comm.MessageSource;

/**
 * The seam between the game chat (hub, server thread) and Herry's dialogue (worker threads). The plugin creates one for its
 * whole life; the hub asks it about every chat line. While no conversation is attached it refuses everything, so Minecraft
 * chat keeps its rule-based answers whenever Discord is off, still starting or already stopping. No Bukkit types here: the
 * plugin passes the lookups in.
 */
public final class MinecraftChatRelay implements CommunicationHub.Dialogue, AutoCloseable {
    /** A chat speaker as the dialogue knows them. {@code verified} is false where names can be faked (offline-mode servers). */
    record Speaker(String id, String name, boolean verified) {
        Speaker {
            if (id == null || !id.matches("mc-[0-9a-f]{32}") || name == null || name.isBlank() || name.length() > 64)
                throw new IllegalArgumentException("chat speaker");
        }
    }
    /** An answer for the chat. It is sent only while {@code current} still holds; {@code delivered} then runs on the server thread. */
    record Reply(IncomingMessage origin, String text, BooleanSupplier current, Runnable delivered) {
        Reply {
            java.util.Objects.requireNonNull(origin); java.util.Objects.requireNonNull(current); java.util.Objects.requireNonNull(delivered);
            if (text == null || text.isBlank() || text.length() > 256) throw new IllegalArgumentException("chat reply text");
        }
    }
    /** The dialogue side as seen from the server thread. Every method returns at once and never touches disk or network. */
    interface Lane {
        String target();
        String called(String text);
        boolean following(Speaker speaker);
        boolean offer(Speaker speaker, String body, Stage stage, IncomingMessage origin);
        void said(String text, Speaker to, String question);
    }

    private final CommunicationHub hub;
    private final BooleanSupplier mainThread, verifiedNames;
    private final Function<String, UUID> playerId;
    private final AtomicReference<Lane> lane = new AtomicReference<>();
    private final ArrayBlockingQueue<Reply> outbox = new ArrayBlockingQueue<>(16);
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;

    /**
     * Construct on the server thread; it registers itself with the hub.
     *
     * @param playerId      UUID of the online player with exactly this name, or null
     * @param verifiedNames whether a player's name proves who they are (the server's online mode)
     */
    public MinecraftChatRelay(CommunicationHub hub, BooleanSupplier mainThread, Function<String, UUID> playerId, BooleanSupplier verifiedNames) {
        this.hub = java.util.Objects.requireNonNull(hub); this.mainThread = java.util.Objects.requireNonNull(mainThread);
        this.playerId = java.util.Objects.requireNonNull(playerId); this.verifiedNames = java.util.Objects.requireNonNull(verifiedNames);
        requireMain(); hub.setDialogue(this);
    }

    /** Lifecycle worker, once the connection is ready. */
    void attach(Lane value) { if (!closed) lane.set(java.util.Objects.requireNonNull(value)); }
    /** Any thread, first thing when the connection stops. Queued replies of that lane are no longer current and are dropped. */
    void detach(Lane value) { lane.compareAndSet(value, null); }
    /** Any thread. False when the reply cannot be queued; the caller drops it and nothing is sent anywhere else. */
    boolean reply(Reply reply) {
        if (closed || lane.get() == null) return false;
        if (outbox.offer(java.util.Objects.requireNonNull(reply))) return true;
        dropped.incrementAndGet(); return false;
    }
    public boolean attached() { return !closed && lane.get() != null; }
    public long droppedReplies() { return dropped.get(); }

    @Override public String participant() { Lane current = lane.get(); return closed || current == null ? "" : current.target(); }
    @Override public String called(String text) { Lane current = lane.get(); return closed || current == null ? null : current.called(text); }
    @Override public boolean following(IncomingMessage message) {
        Lane current = lane.get(); Speaker speaker = current == null ? null : speaker(message);
        return speaker != null && guarded(() -> current.following(speaker));
    }
    @Override public boolean offer(IncomingMessage message, String body, Stage stage) {
        Lane current = lane.get(); Speaker speaker = current == null ? null : speaker(message);
        return speaker != null && guarded(() -> current.offer(speaker, body, stage, message));
    }
    @Override public void said(String text, IncomingMessage replyTo) {
        Lane current = lane.get();
        if (closed || current == null || text == null || text.isBlank()) return;
        Speaker speaker = replyTo == null ? null : speaker(replyTo);
        // An answer to someone the relay cannot identify is not recorded as an announcement instead.
        if (replyTo != null && speaker == null) return;
        guarded(() -> { current.said(text, speaker, replyTo == null ? "" : replyTo.text()); return true; });
    }

    /** Server thread, every couple of ticks. Sends the replies whose turn is still the newest one of its speaker. */
    public void drain() {
        requireMain();
        for (int taken = 0; taken < 4; taken++) {
            Reply reply = outbox.poll();
            if (reply == null) return;
            Lane current = lane.get();
            if (closed || current == null) continue;
            guarded(() -> {
                if (reply.current().getAsBoolean() && hub.deliver(current.target(), reply.text(), reply.origin())) reply.delivered().run();
                return true;
            });
        }
    }
    /** Server thread, when the plugin goes down. After this the hub no longer asks and nothing more is sent. */
    @Override public void close() {
        requireMain();
        if (closed) return;
        closed = true; lane.set(null); outbox.clear(); hub.setDialogue(null);
    }

    private Speaker speaker(IncomingMessage message) {
        if (closed || message == null || message.source() != MessageSource.IN_GAME) return null;
        try {
            UUID id = playerId.apply(message.sender());
            return id == null ? null : new Speaker("mc-" + id.toString().replace("-", ""), message.sender(), verifiedNames.getAsBoolean());
        } catch (RuntimeException unknown) { return null; }
    }
    /** A dialogue fault must never break game chat: the line simply falls back to the rule-based answer. */
    private static boolean guarded(BooleanSupplier call) {
        try { return call.getAsBoolean(); }
        catch (RuntimeException failed) { return false; }
    }
    private void requireMain() { if (!mainThread.getAsBoolean()) throw new IllegalStateException("Minecraft chat relay requires the server thread"); }
}
