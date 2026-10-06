package me.herry.minecraftAI.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import me.herry.minecraftAI.ai.comm.ChatChannel;
import me.herry.minecraftAI.ai.comm.CommunicationHub;
import me.herry.minecraftAI.ai.comm.CommunicationHub.Dialogue.Stage;
import me.herry.minecraftAI.ai.comm.IncomingMessage;
import me.herry.minecraftAI.ai.comm.Intent;
import me.herry.minecraftAI.ai.comm.MessageSource;
import me.herry.minecraftAI.ai.comm.OutgoingMessage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftChatRelayTest {
    private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final AtomicBoolean main = new AtomicBoolean(true), verified = new AtomicBoolean(true);
    private final List<OutgoingMessage> chat = new ArrayList<>();
    private final CommunicationHub hub = hub();
    private CommunicationHub hub() {
        var created = new CommunicationHub(true, 100, () -> 0);
        created.addChannel(new ChatChannel() {
            @Override public MessageSource source() { return MessageSource.IN_GAME; }
            @Override public void send(OutgoingMessage message) { chat.add(message); }
        });
        created.join(new CommunicationHub.Participant() {
            @Override public String name() { return "Bot"; }
            @Override public List<String> respond(IncomingMessage message, Intent intent) { return List.of("규칙 대답"); }
        });
        return created;
    }
    private MinecraftChatRelay relay() { return new MinecraftChatRelay(hub, main::get, name -> name.equals("Steve") ? STEVE : null, verified::get); }
    private static final class Lane implements MinecraftChatRelay.Lane {
        final List<String> seen = new ArrayList<>(); boolean accept = true, fail;
        @Override public String target() { return "Bot"; }
        @Override public String called(String text) { return text.startsWith("해리야 ") ? text.substring(4) : null; }
        @Override public boolean following(MinecraftChatRelay.Speaker speaker) { seen.add("following " + speaker.name()); return accept; }
        @Override public boolean offer(MinecraftChatRelay.Speaker speaker, String body, Stage stage, IncomingMessage origin) {
            if (fail) throw new IllegalStateException("lane fault");
            seen.add(stage + " " + speaker.id() + " " + speaker.name() + " " + speaker.verified() + " " + body); return accept;
        }
        @Override public void said(String text, MinecraftChatRelay.Speaker to, String question) { seen.add("said " + (to == null ? "-" : to.name()) + " " + question + " -> " + text); }
    }
    private static IncomingMessage line(String sender, String text) { return IncomingMessage.inGame(sender, text, false); }

    @Test void withoutAConversationEveryLineKeepsItsRuleAnswer() {
        var relay = relay();
        assertEquals("", relay.participant()); assertNull(relay.called("해리야 안녕")); assertFalse(relay.attached());
        assertFalse(relay.following(line("Steve", "안녕"))); assertFalse(relay.offer(line("Steve", "안녕"), "안녕", Stage.FREE_TALK));
        assertEquals(1, hub.receive(line("Steve", "Bot 안녕"))); assertEquals("규칙 대답", chat.getFirst().text());
        assertEquals(0, hub.receive(line("Steve", "해리야 안녕")));
    }
    @Test void theServerThreadIsRequiredForConstructionDrainAndClose() {
        main.set(false); assertThrows(IllegalStateException.class, this::relay);
        main.set(true); var relay = relay(); main.set(false);
        assertThrows(IllegalStateException.class, relay::drain); assertThrows(IllegalStateException.class, relay::close);
    }
    @Test void aSpeakerIsAnOnlinePlayerWithAStableIdAndTheServersIdentityGuarantee() {
        var relay = relay(); var lane = new Lane(); relay.attach(lane);
        assertTrue(relay.attached()); assertEquals("Bot", relay.participant()); assertEquals("안녕", relay.called("해리야 안녕"));
        assertTrue(relay.offer(line("Steve", "Bot 안녕"), "안녕", Stage.FREE_TALK));
        assertEquals("FREE_TALK mc-11111111222233334444555555555555 Steve true 안녕", lane.seen.getLast());
        verified.set(false); relay.offer(line("Steve", "x"), "x", Stage.PERSONAL);
        assertEquals("PERSONAL mc-11111111222233334444555555555555 Steve false x", lane.seen.getLast());
        int before = lane.seen.size();
        assertFalse(relay.offer(line("Ghost", "Bot 안녕"), "안녕", Stage.FREE_TALK), "A name that is not an online player is nobody");
        assertFalse(relay.following(line("Ghost", "안녕")));
        assertFalse(relay.offer(new IncomingMessage(MessageSource.SYSTEM, "Steve", "Bot 안녕", false, true), "안녕", Stage.FREE_TALK));
        assertEquals(before, lane.seen.size());
        assertTrue(relay.following(line("Steve", "진짜?"))); assertEquals("following Steve", lane.seen.getLast());
    }
    @Test void aFaultInTheConversationFallsBackToTheRuleAnswerInsteadOfBreakingChat() {
        var relay = relay(); var lane = new Lane(); lane.fail = true; relay.attach(lane);
        assertEquals(1, hub.receive(line("Steve", "Bot 안녕"))); assertEquals("규칙 대답", chat.getFirst().text());
    }
    @Test void ruleAnswersAndAnnouncementsAreToldToTheConversation() {
        var relay = relay(); var lane = new Lane(); lane.accept = false; relay.attach(lane);
        assertEquals(1, hub.receive(line("Steve", "Bot 뭐 해?")));
        assertEquals("said Steve Bot 뭐 해? -> 규칙 대답", lane.seen.getLast());
        assertTrue(hub.say("Bot", "나무를 캐러 갈게요.", true)); assertEquals("said -  -> 나무를 캐러 갈게요.", lane.seen.getLast());
        int before = lane.seen.size();
        relay.said("대답", line("Ghost", "Bot 뭐 해?")); relay.said(" ", null);
        assertEquals(before, lane.seen.size(), "An answer to an unknown player is not recorded as an announcement");
    }
    @Test void repliesGoOutOnTheServerThreadOnlyWhileTheirTurnIsCurrent() {
        var relay = relay(); var lane = new Lane(); var question = line("Steve", "Bot 안녕"); var delivered = new ArrayList<String>();
        assertFalse(relay.reply(new MinecraftChatRelay.Reply(question, "아직 연결 전", () -> true, () -> delivered.add("early"))));
        relay.attach(lane);
        assertTrue(relay.reply(new MinecraftChatRelay.Reply(question, "반가워요.", () -> true, () -> delivered.add("first"))));
        assertTrue(relay.reply(new MinecraftChatRelay.Reply(question, "낡은 대답", () -> false, () -> delivered.add("stale"))));
        assertTrue(chat.isEmpty(), "Nothing is sent from the worker thread");
        relay.drain();
        assertEquals(1, chat.size()); assertEquals("Bot", chat.getFirst().speaker()); assertEquals("반가워요.", chat.getFirst().text());
        assertSame(question, chat.getFirst().replyTo()); assertEquals(List.of("first"), delivered);
        hub.leave("Bot");
        assertTrue(relay.reply(new MinecraftChatRelay.Reply(question, "떠난 뒤", () -> true, () -> delivered.add("gone")))); relay.drain();
        assertEquals(1, chat.size()); assertEquals(List.of("first"), delivered);
    }
    @Test void aFullOutboxDropsTheReplyAndCountsIt() {
        var relay = relay(); relay.attach(new Lane()); var question = line("Steve", "Bot 안녕");
        for (int index = 0; index < 16; index++) assertTrue(relay.reply(new MinecraftChatRelay.Reply(question, "대답 " + index, () -> true, () -> {})));
        assertFalse(relay.reply(new MinecraftChatRelay.Reply(question, "넘침", () -> true, () -> {}))); assertEquals(1, relay.droppedReplies());
        relay.drain(); assertEquals(4, chat.size(), "A tick sends a bounded number of lines");
    }
    @Test void detachAndCloseStopEverythingAndGiveChatBackToTheRules() {
        var relay = relay(); var lane = new Lane(); relay.attach(lane); var question = line("Steve", "Bot 안녕");
        assertTrue(relay.reply(new MinecraftChatRelay.Reply(question, "보내기 전", () -> true, () -> fail("delivered after detach"))));
        relay.detach(new Lane()); assertTrue(relay.attached(), "Only the attached conversation can detach itself");
        relay.detach(lane); assertFalse(relay.attached()); relay.drain(); assertTrue(chat.isEmpty());
        assertFalse(relay.reply(new MinecraftChatRelay.Reply(question, "분리 뒤", () -> true, () -> {})));
        assertEquals(1, hub.receive(question)); assertEquals("규칙 대답", chat.getFirst().text());
        relay.attach(lane); relay.close(); relay.close();
        assertEquals("", relay.participant()); relay.attach(lane); assertFalse(relay.attached());
        assertEquals(1, hub.receive(question)); assertEquals(2, chat.size());
    }
    @Test void replyAndSpeakerBoundsAreChecked() {
        var question = line("Steve", "Bot 안녕");
        assertThrows(IllegalArgumentException.class, () -> new MinecraftChatRelay.Reply(question, " ", () -> true, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftChatRelay.Reply(question, "가".repeat(257), () -> true, () -> {}));
        assertThrows(NullPointerException.class, () -> new MinecraftChatRelay.Reply(null, "대답", () -> true, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftChatRelay.Speaker("123456789012345678", "Steve", true));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftChatRelay.Speaker("mc-11111111222233334444555555555555", " ", true));
    }
}
