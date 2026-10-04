package me.herry.minecraftAI.discord;

import me.herry.minecraftAI.ai.comm.CommunicationHub;
import me.herry.minecraftAI.ai.comm.IncomingMessage;
import me.herry.minecraftAI.ai.comm.Intent;
import me.herry.minecraftAI.ai.comm.MessageSource;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Acknowledged requests cross to the Paper main thread through a bounded queue, never via LLM output. */
public final class DiscordGameBridge implements AutoCloseable {
    public enum Mode { QUESTION, CONTROL }
    public enum Admission { RESERVED, DUPLICATE, BUSY, OUT_OF_SCOPE, CLOSED }
    public enum Code { OK, NO_TARGET, NO_REPLY, QUESTION_ONLY, CONTROL_DISABLED, NOT_ALLOWED, EXPIRED, DISABLED, FAILED }
    public record Request(String requestId, String guildId, String channelId, String userId,
                          String sender, String targetAi, String text, Mode mode) {
        public Request {
            id(requestId); id(guildId); id(channelId); id(userId); id(sender); id(targetAi);
            if (text == null || text.isBlank() || text.length() > 2000 || mode == null) throw new IllegalArgumentException("Discord request body");
        }
    }
    public record Reservation(UUID claim, long generation, long expiresAt, Request request) {}
    public record Reserved(Admission admission, Reservation reservation) {}
    public record ReplyRoute(UUID claim, long generation, long expiresAt) {}
    public record Result(Request request, ReplyRoute route, Code code, List<String> lines) {
        public Result { lines = List.copyOf(lines); }
    }
    private enum State { RESERVED, QUEUED, DONE }
    private static final class Entry {
        final Reservation reservation;
        State state = State.RESERVED;
        Entry(Reservation reservation) { this.reservation = reservation; }
    }
    private final CommunicationHub hub;
    private final DiscordChatChannel channel = new DiscordChatChannel();
    private final BooleanSupplier mainThread;
    private final Predicate<Request> allowedScope;
    private final Predicate<String> currentAdmin;
    private final LongSupplier clock;
    private final boolean controlEnabled;
    private final Map<String, Entry> remembered = new LinkedHashMap<>();
    private final ArrayDeque<Entry> inbox = new ArrayDeque<>();
    private final ArrayBlockingQueue<Result> outbox = new ArrayBlockingQueue<>(64);
    private boolean closed;
    private long generation = 1, lostReplies;

    /** Construct on the main thread. Scope checks use immutable Discord IDs; admin checks run only in drain. */
    public DiscordGameBridge(CommunicationHub hub, BooleanSupplier mainThread, Predicate<Request> allowedScope,
                             Predicate<String> currentAdmin, boolean controlEnabled, LongSupplier clock) {
        this.hub = java.util.Objects.requireNonNull(hub); this.mainThread = java.util.Objects.requireNonNull(mainThread);
        this.allowedScope = java.util.Objects.requireNonNull(allowedScope); this.currentAdmin = java.util.Objects.requireNonNull(currentAdmin);
        this.clock = java.util.Objects.requireNonNull(clock); this.controlEnabled = controlEnabled;
        requireMain(); hub.addChannel(channel);
    }
    public synchronized Reserved reserve(Request request) {
        if (closed) return new Reserved(Admission.CLOSED, null);
        if (!allowedScope.test(request)) return new Reserved(Admission.OUT_OF_SCOPE, null);
        long now = clock.getAsLong();
        remembered.values().forEach(entry -> { if (entry.state == State.RESERVED && now >= entry.reservation.expiresAt) entry.state = State.DONE; });
        remembered.values().removeIf(entry -> entry.state == State.DONE && now >= entry.reservation.expiresAt + 900_000);
        if (remembered.containsKey(request.requestId)) return new Reserved(Admission.DUPLICATE, null);
        if (remembered.size() >= 512 || remembered.values().stream().filter(e -> e.state != State.DONE).count() >= 64)
            return new Reserved(Admission.BUSY, null);
        Reservation reservation = new Reservation(UUID.randomUUID(), generation, now + 15_000, request);
        remembered.put(request.requestId, new Entry(reservation));
        return new Reserved(Admission.RESERVED, reservation);
    }
    /** Invoke only after the interaction defer/ack succeeds; failure never enqueues a game request. */
    public synchronized boolean acknowledged(Reservation reservation) {
        Entry entry = entry(reservation);
        if (entry == null || entry.state != State.RESERVED) return false;
        if (clock.getAsLong() >= reservation.expiresAt || inbox.size() >= 32) { entry.state = State.DONE; return false; }
        entry.state = State.QUEUED; inbox.addLast(entry); return true;
    }
    public synchronized void acknowledgementFailed(Reservation reservation) {
        Entry entry = entry(reservation); if (entry != null && entry.state == State.RESERVED) entry.state = State.DONE;
    }
    public synchronized int drain(int maxRequests) {
        requireMain();
        if (maxRequests < 1 || maxRequests > 16) throw new IllegalArgumentException("Discord drain budget");
        int drained = 0;
        while (!closed && drained < maxRequests && !inbox.isEmpty()) {
            Entry entry = inbox.removeFirst(); entry.state = State.DONE; drained++;
            Request request = entry.reservation.request;
            Result result;
            try { result = process(entry.reservation); }
            catch (RuntimeException e) { result = result(entry.reservation, Code.FAILED); }
            // A lost network reply does not repeat the game request.
            if (!outbox.offer(result)) lostReplies++;
        }
        return drained;
    }
    /** Outbox sender consumes results off the server thread and applies route TTL/allowed mentions itself. */
    public Result pollResult() { return outbox.poll(); }
    public synchronized boolean replyAllowed(Result result) {
        if (closed || result == null || !allowedScope.test(result.request)) return false;
        Entry entry = remembered.get(result.request.requestId);
        return entry != null && entry.state == State.DONE && entry.reservation.request.equals(result.request)
                && entry.reservation.claim.equals(result.route.claim) && entry.reservation.generation == result.route.generation
                && result.route.generation == generation && result.route.expiresAt == entry.reservation.expiresAt + 15_000
                && clock.getAsLong() < result.route.expiresAt;
    }
    public synchronized long lostReplies() { return lostReplies; }
    private Result process(Reservation reservation) {
        Request request = reservation.request;
        if (clock.getAsLong() >= reservation.expiresAt || reservation.generation != generation) return result(reservation, Code.EXPIRED);
        if (!allowedScope.test(request)) return result(reservation, Code.NOT_ALLOWED);
        if (!hub.enabled()) return result(reservation, Code.DISABLED);
        if (hub.participantNames().stream().noneMatch(name -> name.equalsIgnoreCase(request.targetAi))) return result(reservation, Code.NO_TARGET);
        boolean trusted = request.mode == Mode.CONTROL && controlEnabled && currentAdmin.test(request.userId);
        if (request.mode == Mode.CONTROL && !controlEnabled) return result(reservation, Code.CONTROL_DISABLED);
        if (request.mode == Mode.CONTROL && !trusted) return result(reservation, Code.NOT_ALLOWED);
        IncomingMessage original = new IncomingMessage(MessageSource.DISCORD, request.sender, request.text, true, trusted);
        boolean[] questionRejected = {false};
        List<String> lines = channel.collect(original, () -> hub.receiveTo(request.targetAi, original, intent -> {
            boolean allowed = request.mode == Mode.CONTROL || question(intent);
            if (!allowed) questionRejected[0] = true;
            return allowed;
        }));
        return new Result(request, route(reservation), questionRejected[0] ? Code.QUESTION_ONLY : lines.isEmpty() ? Code.NO_REPLY : Code.OK, lines);
    }
    private static boolean question(Intent intent) {
        return switch (intent.type()) { case ASK_STATUS, ASK_REASON, ASK_HAVE, ASK_LOCATION, NONE -> true; default -> false; };
    }
    private Entry entry(Reservation reservation) {
        if (closed || reservation == null || reservation.generation != generation) return null;
        Entry entry = remembered.get(reservation.request.requestId);
        return entry != null && entry.reservation.equals(reservation) ? entry : null;
    }
    private static ReplyRoute route(Reservation reservation) { return new ReplyRoute(reservation.claim, reservation.generation, reservation.expiresAt + 15_000); }
    private static Result result(Reservation reservation, Code code) { return new Result(reservation.request, route(reservation), code, List.of()); }
    @Override public synchronized void close() {
        requireMain(); if (closed) return;
        closed = true; generation++; inbox.clear(); remembered.clear(); outbox.clear(); hub.removeChannel(channel);
    }
    private void requireMain() { if (!mainThread.getAsBoolean()) throw new IllegalStateException("Discord game bridge requires main thread"); }
    private static void id(String value) {
        if (value == null || value.isBlank() || value.length() > 100) throw new IllegalArgumentException("Discord request identifier");
    }
}
