package me.herry.minecraftAI.discord;

import me.herry.minecraftAI.ai.comm.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiscordHubRoutingTest {
    private CommunicationHub hub(boolean enabled) { return new CommunicationHub(enabled, 100, () -> 0); }
    private CommunicationHub.Participant participant(String name, AtomicInteger calls) {
        return new CommunicationHub.Participant() {
            public String name() { return name; }
            public List<String> respond(IncomingMessage message, Intent intent) { calls.incrementAndGet(); return List.of(name + ":" + intent.type()); }
        };
    }
    private IncomingMessage message() { return new IncomingMessage(MessageSource.DISCORD, "SameName", "Alpha Beta 뭐해?", true, false); }
    @Test void exactTargetOnlyAnswersEvenForDirectInputNamingBothAis() {
        var hub = hub(true); var first = new AtomicInteger(); var second = new AtomicInteger(); var channel = new DiscordChatChannel();
        hub.join(participant("Alpha", first)); hub.join(participant("Beta", second)); hub.addChannel(channel);
        var original = new IncomingMessage(MessageSource.DISCORD, "SameName", "다이아 찾았어?", false, false);
        assertEquals(List.of("Alpha:ASK_HAVE"), channel.collect(original, () -> hub.receiveTo("Alpha", original)));
        assertEquals(1, first.get()); assertEquals(0, second.get());
    }
    @Test void receiveToRoutesExactlyAndPreservesOriginalIdentity() {
        var hub = hub(true); var first = new AtomicInteger(); var second = new AtomicInteger(); var channel = new DiscordChatChannel();
        hub.join(participant("Alpha", first)); hub.join(participant("Beta", second)); hub.addChannel(channel); var original = message();
        assertEquals(List.of("Beta:ASK_STATUS"), channel.collect(original, () -> assertEquals(1, hub.receiveTo("bEtA", original))));
        assertEquals(0, first.get()); assertEquals(1, second.get()); assertEquals(0, hub.receiveTo("Bet", original));
    }
    @Test void equalValueRequestsHaveSeparateIdentityScopes() {
        var channel = new DiscordChatChannel(); var first = message(); var second = message(); assertEquals(first, second); assertNotSame(first, second);
        var outside = channel.collect(first, () -> {
            var inside = channel.collect(second, () -> {
                channel.send(OutgoingMessage.reply("Bot", "inner", second)); channel.send(OutgoingMessage.reply("Bot", "outer", first));
            }); assertEquals(List.of("inner"), inside);
        }); assertEquals(List.of("outer"), outside);
    }
    @Test void systemFallbackAnnouncementsAndUnscopedRepliesNeverLeak() {
        var channel = new DiscordChatChannel(); var original = message();
        assertTrue(channel.collect(original, () -> {
            channel.send(OutgoingMessage.announcement("Bot", "private announcement", true));
            channel.send(OutgoingMessage.reply("Bot", "private system", new IncomingMessage(MessageSource.SYSTEM, "Admin", "secret", true, true)));
            channel.send(OutgoingMessage.reply("Bot", "unscoped", message()));
        }).isEmpty());
    }
    @Test void failedDeliveryRemovesScopeAndCanNeverCollectItsLateReply() {
        var channel = new DiscordChatChannel(); var original = message();
        assertThrows(IllegalStateException.class, () -> channel.collect(original, () -> { throw new IllegalStateException("participant failed"); }));
        channel.send(OutgoingMessage.reply("Bot", "late", original));
        assertEquals(List.of("fresh"), channel.collect(original, () -> channel.send(OutgoingMessage.reply("Bot", "fresh", original))));
    }
    @Test void permissionFilterRunsAfterCurrentInterpreterBeforeParticipant() {
        var hub = hub(true); var calls = new AtomicInteger(); hub.join(participant("Beta", calls));
        hub.setInterpreter(message -> Intent.of(Intent.Type.AUTONOMOUS));
        assertEquals(0, hub.receiveTo("Beta", message(), intent -> intent.type() == Intent.Type.ASK_STATUS)); assertEquals(0, calls.get());
    }
    @Test void disabledHubDoesNotAnswerAndNamesAreImmutableSnapshot() {
        var hub = hub(false); var calls = new AtomicInteger(); hub.join(participant("Alpha", calls));
        var names = hub.participantNames(); hub.join(participant("Beta", calls)); assertEquals(List.of("Alpha"), names);
        assertThrows(UnsupportedOperationException.class, () -> names.add("Injected")); assertFalse(hub.enabled()); assertEquals(0, hub.receiveTo("Alpha", message()));
    }
}
