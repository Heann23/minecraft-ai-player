package me.herry.minecraftAI.discord;

import me.herry.minecraftAI.ai.comm.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static me.herry.minecraftAI.discord.DiscordGameBridge.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscordGameBridgeTest {
    private long now = 1000;
    private final AtomicBoolean main = new AtomicBoolean(true), admin = new AtomicBoolean(true), scope = new AtomicBoolean(true);
    private final AtomicInteger calls = new AtomicInteger();
    private final CommunicationHub hub = new CommunicationHub(true, 100, () -> 0);
    private IncomingMessage last;
    private DiscordGameBridge bridge(boolean control) {
        hub.join(new CommunicationHub.Participant() {
            public String name() { return "Herry"; }
            public List<String> respond(IncomingMessage message, Intent intent) { calls.incrementAndGet(); last = message; return List.of(intent.type().name()); }
        });
        return new DiscordGameBridge(hub, main::get, request -> scope.get(), ignored -> admin.get(), control, () -> now);
    }
    private Request request(String id, Mode mode, String text) { return new Request(id, "guild", "channel", "user", "Display", "Herry", text, mode); }
    private void enqueue(DiscordGameBridge bridge, Request request) { var reserved = bridge.reserve(request); assertEquals(Admission.RESERVED, reserved.admission()); assertTrue(bridge.acknowledged(reserved.reservation())); }
    @Test void acknowledgedQuestionIsMainThreadOnlyAndUntrusted() {
        try (var bridge = bridge(false)) {
            enqueue(bridge, request("1", Mode.QUESTION, "지금 뭐해?")); main.set(false); assertThrows(IllegalStateException.class, () -> bridge.drain(1)); main.set(true);
            assertEquals(1, bridge.drain(1)); var result = bridge.pollResult(); assertEquals(Code.OK, result.code()); assertEquals(List.of("ASK_STATUS"), result.lines()); assertFalse(last.trusted()); assertTrue(bridge.replyAllowed(result));
        } finally { main.set(true); }
    }
    @Test void acknowledgementFailureNeverCallsGameAndDuplicateDoesNotRetry() {
        try (var bridge = bridge(true)) {
            var request = request("1", Mode.CONTROL, "멈춰"); var reserved = bridge.reserve(request); bridge.acknowledgementFailed(reserved.reservation());
            assertFalse(bridge.acknowledged(reserved.reservation())); assertEquals(0, bridge.drain(1)); assertEquals(0, calls.get()); assertEquals(Admission.DUPLICATE, bridge.reserve(request).admission());
        }
    }
    @Test void duplicateAcknowledgementCannotExecuteTwice() {
        try (var bridge = bridge(true)) {
            var reserved = bridge.reserve(request("1", Mode.CONTROL, "멈춰")); assertTrue(bridge.acknowledged(reserved.reservation())); assertFalse(bridge.acknowledged(reserved.reservation()));
            assertEquals(1, bridge.drain(16)); assertEquals(0, bridge.drain(16)); assertEquals(1, calls.get());
        }
    }
    @Test void permissionIsRecheckedAtExecutionRatherThanAdmission() {
        try (var bridge = bridge(true)) { enqueue(bridge, request("1", Mode.CONTROL, "멈춰")); admin.set(false); bridge.drain(1); assertEquals(Code.NOT_ALLOWED, bridge.pollResult().code()); assertEquals(0, calls.get()); }
    }
    @Test void controlDisabledNeverLetsEvenAdminControl() {
        try (var bridge = bridge(false)) { enqueue(bridge, request("1", Mode.CONTROL, "멈춰")); bridge.drain(1); assertEquals(Code.CONTROL_DISABLED, bridge.pollResult().code()); assertEquals(0, calls.get()); }
    }
    @Test void questionModeBlocksEveryMutatingIntentIncludingAutonomousAndCustomInterpreter() {
        try (var bridge = bridge(true)) {
            for (Intent.Type type : List.of(Intent.Type.AUTONOMOUS, Intent.Type.STOP, Intent.Type.RESUME, Intent.Type.GATHER, Intent.Type.GO_HOME)) {
                hub.setInterpreter(message -> Intent.of(type)); enqueue(bridge, request(type.name(), Mode.QUESTION, "지금 뭐해?")); bridge.drain(1); assertEquals(Code.QUESTION_ONLY, bridge.pollResult().code());
            } assertEquals(0, calls.get());
        }
    }
    @Test void expiredRequestAndChangedScopeCannotExecute() {
        try (var bridge = bridge(true)) {
            enqueue(bridge, request("1", Mode.CONTROL, "멈춰")); now += 15_000; bridge.drain(1); assertEquals(Code.EXPIRED, bridge.pollResult().code());
            enqueue(bridge, request("2", Mode.CONTROL, "멈춰")); scope.set(false); bridge.drain(1); assertEquals(Code.NOT_ALLOWED, bridge.pollResult().code()); assertEquals(0, calls.get());
        }
    }
    @Test void queueOverflowAndBudgetDoNotRunUnacceptedRequest() {
        try (var bridge = bridge(false)) {
            for (int i = 0; i < 32; i++) enqueue(bridge, request("r" + i, Mode.QUESTION, "뭐해?"));
            var extra = bridge.reserve(request("overflow", Mode.QUESTION, "뭐해?")); assertFalse(bridge.acknowledged(extra.reservation()));
            assertEquals(16, bridge.drain(16)); assertEquals(16, calls.get()); assertEquals(16, bridge.drain(16)); assertEquals(32, calls.get());
        }
    }
    @Test void abandonedExpiredReservationsCannotHoldCapacityForever() {
        try (var bridge = bridge(false)) { for (int i = 0; i < 64; i++) assertEquals(Admission.RESERVED, bridge.reserve(request("r" + i, Mode.QUESTION, "뭐해?" )).admission());
            assertEquals(Admission.BUSY, bridge.reserve(request("busy", Mode.QUESTION, "뭐해?" )).admission()); now += 15_000;
            assertEquals(Admission.RESERVED, bridge.reserve(request("fresh", Mode.QUESTION, "뭐해?" )).admission());
        }
    }
    @Test void missingTargetAndShutdownCancelRequestsAndReplies() {
        var bridge = bridge(false); enqueue(bridge, request("1", Mode.QUESTION, "뭐해?")); hub.leave("Herry"); bridge.drain(1); assertEquals(Code.NO_TARGET, bridge.pollResult().code());
        hub.join(new CommunicationHub.Participant() { public String name() { return "Herry"; } public List<String> respond(IncomingMessage message, Intent intent) { return List.of("reply"); } });
        enqueue(bridge, request("2", Mode.QUESTION, "뭐해?")); bridge.drain(1); var result = bridge.pollResult(); bridge.close(); assertFalse(bridge.replyAllowed(result)); assertEquals(Admission.CLOSED, bridge.reserve(request("3", Mode.QUESTION, "뭐해?" )).admission());
    }
    @Test void originalRequestCannotReplayWhileInteractionIsStillLive() {
        try (var bridge = bridge(false)) {
            var request = request("1", Mode.QUESTION, "뭐해?"); enqueue(bridge, request); bridge.drain(1); now += 31_000;
            assertEquals(Admission.DUPLICATE, bridge.reserve(request).admission()); assertEquals(1, calls.get());
        }
    }
    @Test void sameIdCannotAuthorizeReplyForDifferentUserOrChannel() {
        try (var bridge = bridge(false)) {
            enqueue(bridge, request("1", Mode.QUESTION, "뭐해?")); bridge.drain(1); var original = bridge.pollResult();
            var wrong = new Request("1", "guild", "other-channel", "other-user", "Display", "Herry", "뭐해?", Mode.QUESTION);
            assertFalse(bridge.replyAllowed(new Result(wrong, original.route(), original.code(), original.lines()))); assertTrue(bridge.replyAllowed(original));
        }
    }
    @Test void reusedRequestIdCannotReauthorizeOlderReplyRoute() {
        try (var bridge = bridge(false)) {
            var request = request("1", Mode.QUESTION, "뭐해?"); enqueue(bridge, request); bridge.drain(1); var old = bridge.pollResult(); now += 930_000;
            enqueue(bridge, request); bridge.drain(1); var fresh = bridge.pollResult(); assertFalse(bridge.replyAllowed(old)); assertTrue(bridge.replyAllowed(fresh));
            assertNotEquals(old.route().claim(), fresh.route().claim());
        }
    }
}
