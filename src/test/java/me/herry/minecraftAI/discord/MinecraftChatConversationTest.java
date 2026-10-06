package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.herry.minecraftAI.ai.comm.ChatChannel;
import me.herry.minecraftAI.ai.comm.CommunicationHub;
import me.herry.minecraftAI.ai.comm.CommunicationHub.Dialogue.Stage;
import me.herry.minecraftAI.ai.comm.IncomingMessage;
import me.herry.minecraftAI.ai.comm.Intent;
import me.herry.minecraftAI.ai.comm.MessageSource;
import me.herry.minecraftAI.ai.comm.OutgoingMessage;
import me.herry.minecraftAI.discord.MinecraftChatRelay.Speaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftChatConversationTest {
    @TempDir Path directory;
    private static final String GUILD = "12345678901234567";
    private static final Speaker STEVE = new Speaker("mc-" + "1".repeat(32), "Steve", true), ALEX = new Speaker("mc-" + "2".repeat(32), "Alex", true),
            GUEST = new Speaker("mc-" + "3".repeat(32), "Guest", false);
    private final AtomicLong wall = new AtomicLong(1_000_000), monotonic = new AtomicLong(10_000);
    private final List<OutgoingMessage> chat = new CopyOnWriteArrayList<>();
    private final List<String> codes = new CopyOnWriteArrayList<>();
    private final List<ResponsePipeline.Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger models = new AtomicInteger();
    private final CommunicationHub hub = new CommunicationHub(true, 100, () -> 0);
    private MinecraftChatRelay relay;
    private DiscordSettings settings() {
        return new DiscordSettings(true, "TEST_TOKEN", GUILD, "12345678901234568", "herry", false, 60_000, 32,
                new DiscordMemoryStore.BackupPolicy(false, 600_000, 24, 7, 128L * 1024 * 1024));
    }
    private DiscordMemoryStore store() throws Exception { return new DiscordMemoryStore(directory, settings().backup(), wall::get); }
    private MinecraftChatConversation lane(DiscordMemoryStore store, ResponsePipeline.Model model) {
        hub.addChannel(new ChatChannel() {
            @Override public MessageSource source() { return MessageSource.IN_GAME; }
            @Override public void send(OutgoingMessage message) { chat.add(message); }
        });
        hub.join(new CommunicationHub.Participant() {
            @Override public String name() { return "Bot"; }
            @Override public List<String> respond(IncomingMessage message, Intent intent) { return List.of("규칙 대답"); }
        });
        relay = new MinecraftChatRelay(hub, () -> true, name -> UUID.randomUUID(), () -> true);
        var created = new MinecraftChatConversation(settings(), store, model, "Bot", relay, codes::add, wall::get, monotonic::get);
        relay.attach(created); return created;
    }
    private ResponsePipeline.Model answering(String text) { return request -> { models.incrementAndGet(); requests.add(request); return text; }; }
    private static IncomingMessage line(Speaker speaker, String text) { return IncomingMessage.inGame(speaker.name(), text, false); }
    private boolean say(MinecraftChatConversation lane, Speaker speaker, String text, Stage stage) { return lane.offer(speaker, text, stage, line(speaker, text)); }
    /** The server thread's part: drain until the expected number of chat lines went out. */
    private OutgoingMessage reply(int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (chat.size() < count && System.nanoTime() < end) { relay.drain(); Thread.sleep(2); }
        assertEquals(count, chat.size(), "chat lines"); return chat.get(count - 1);
    }
    private List<DiscordMemory.Fact> facts(DiscordMemoryStore store, Speaker speaker) {
        return store.visible(new DiscordMemory.Subject(GUILD, "herry", speaker.id()), Set.of(speaker.id())).join();
    }
    private void later(long millis) { monotonic.addAndGet(millis); wall.addAndGet(millis); }

    @Test void freeTalkIsAnsweredByTheModelAsOneChatLineAndOpensAShortFollowUp() throws Exception {
        try (var store = store(); var lane = lane(store, answering("반가워요.\n오늘도 같이 놀아요."))) {
            assertEquals("Bot", lane.target()); assertEquals("안녕", lane.called("해리야. 안녕")); assertNull(lane.called("해리포터 봤어?"));
            assertFalse(lane.following(STEVE));
            var question = line(STEVE, "Bot 안녕");
            assertTrue(lane.offer(STEVE, "안녕", Stage.FREE_TALK, question));
            var sent = reply(1);
            assertEquals("Bot", sent.speaker()); assertEquals("반가워요. 오늘도 같이 놀아요.", sent.text()); assertSame(question, sent.replyTo());
            var request = requests.getFirst();
            assertEquals(STEVE.id(), request.turn().userId()); assertEquals("안녕", DialogueContext.currentInput(request)); assertTrue(request.memory().isEmpty());
            assertTrue(lane.following(STEVE));
            assertEquals(List.of("게임 채팅 Steve>Herry:안녕", "Herry>게임 채팅 Steve:반가워요. 오늘도 같이 놀아요."),
                    lane.recent().stream().map(l -> l.speaker() + ">" + l.target() + ":" + l.text()).toList());
            assertFalse(lane.following(ALEX), "Another player's line is not a follow-up"); assertFalse(lane.following(STEVE), "and it ends the follow-up");
            later(2_000); assertTrue(say(lane, STEVE, "그건 왜?", Stage.FREE_TALK)); reply(2);
            assertTrue(requests.getLast().context().stream().anyMatch(l -> l.assistant() && l.text().startsWith("반가워요.")), "The player's own earlier exchange is context");
            assertTrue(lane.following(STEVE)); later(30_000); assertFalse(lane.following(STEVE), "The follow-up window is short");
            assertTrue(codes.isEmpty(), codes.toString());
        }
    }
    @Test void aBareCallIsAnsweredWithTheLineItself() throws Exception {
        try (var store = store(); var lane = lane(store, answering("네, 부르셨어요?"))) {
            assertTrue(lane.offer(STEVE, "", Stage.FREE_TALK, line(STEVE, "해리야"))); reply(1);
            assertEquals("해리야", DialogueContext.currentInput(requests.getFirst()));
        }
    }
    @Test void thePersonalStageTakesOnlyWholeSettingSentencesAndStoresThemWithoutTheModel() throws Exception {
        try (var store = store(); var lane = lane(store, request -> { throw new AssertionError("No model for a setting"); })) {
            for (String talk : List.of("오늘 뭐 할까", "반말해도 돼?", "친구가 장난하지 마 라고 했어", "멈춰")) assertFalse(say(lane, STEVE, talk, Stage.PERSONAL), talk);
            assertTrue(say(lane, STEVE, "내 이름은 민수야", Stage.PERSONAL)); assertEquals("민수님으로 부를게요.", reply(1).text());
            assertEquals("민수", facts(store, STEVE).getFirst().value()); assertTrue(facts(store, STEVE).getFirst().sourceId().startsWith("chat-"));
            assertTrue(say(lane, STEVE, "나한테 반말해도 돼", Stage.PERSONAL)); assertEquals("알겠어. 나한테 반말을 허락한 걸로 기억할게.", reply(2).text());
            assertTrue(say(lane, STEVE, "장난 그만해", Stage.PERSONAL)); assertEquals("미안해. 장난은 멈추고 담백하게 이야기할게.", reply(3).text());
            assertEquals(3, facts(store, STEVE).size()); assertTrue(facts(store, ALEX).isEmpty());
            assertTrue(lane.recent().isEmpty(), "Settings are not part of the public chat log");
            assertTrue(say(lane, STEVE, "내 이름만 잊어 줘", Stage.PERSONAL)); reply(4);
            assertEquals(2, facts(store, STEVE).size());
            assertTrue(say(lane, STEVE, "내 기억 모두 지워 줘", Stage.PERSONAL));
            assertEquals("본인의 저장된 기억과 이전 대화 문맥을 모두 지웠어요. 기본 존댓말로 다시 이야기할게요.", reply(5).text());
            assertTrue(facts(store, STEVE).isEmpty());
        }
    }
    @Test void whereNamesCanBeFakedChatOnlyMakesHerryPlainer() throws Exception {
        try (var store = store(); var lane = lane(store, request -> { throw new AssertionError("No model for a setting"); })) {
            for (String loosening : List.of("내 이름은 민수야", "나한테 반말해도 돼", "장난해도 돼", "내 기억 모두 지워 줘")) {
                assertTrue(say(lane, GUEST, loosening, Stage.PERSONAL), loosening);
                assertEquals(MinecraftChatConversation.UNVERIFIED, reply(chat.size() + 1).text(), loosening);
            }
            assertTrue(facts(store, GUEST).isEmpty());
            assertTrue(say(lane, GUEST, "장난하지 마", Stage.PERSONAL)); assertEquals("미안해요. 장난은 멈추고 담백하게 이야기할게요.", reply(5).text());
            assertEquals("AVOID", facts(store, GUEST).getFirst().value());
            assertTrue(say(lane, GUEST, "내 장난 설정만 지워 줘", Stage.PERSONAL)); assertEquals(MinecraftChatConversation.UNVERIFIED, reply(6).text());
            assertEquals(1, facts(store, GUEST).size());
        }
    }
    @Test void aNewerLineOfTheSamePlayerReplacesTheOlderAnswer() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        try (var store = store(); var lane = lane(store, request -> {
            if (DialogueContext.currentInput(request).equals("첫 질문")) {
                entered.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
            }
            return "두 번째 대답";
        })) {
            assertTrue(say(lane, STEVE, "첫 질문", Stage.FREE_TALK)); assertTrue(entered.await(3, TimeUnit.SECONDS));
            later(2_000); assertTrue(say(lane, STEVE, "둘째 질문", Stage.FREE_TALK));
            assertTrue(interrupted.await(3, TimeUnit.SECONDS)); assertEquals("두 번째 대답", reply(1).text());
            Thread.sleep(50); relay.drain(); assertEquals(1, chat.size()); assertTrue(codes.isEmpty(), codes.toString());
        }
    }
    @Test void aSlowAnswerIsDroppedAfterItsDeadlineInsteadOfAppearingLate() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var store = store(); var lane = lane(store, request -> { entered.countDown(); release.await(); return "너무 늦은 대답"; })) {
            assertTrue(say(lane, STEVE, "질문", Stage.FREE_TALK)); assertTrue(entered.await(3, TimeUnit.SECONDS));
            later(20_000); release.countDown();
            Thread.sleep(100); relay.drain(); relay.drain();
            assertTrue(chat.isEmpty(), "A reply past its deadline is not sent");
        }
    }
    @Test void aBusyPlayerOrARepeatedlyFailingModelGetsTheRuleAnswerInstead() throws Exception {
        try (var store = store(); var lane = lane(store, request -> { models.incrementAndGet(); throw new java.io.IOException("provider down"); })) {
            assertTrue(say(lane, STEVE, "하나", Stage.FREE_TALK)); assertEquals(MinecraftChatConversation.UNAVAILABLE, reply(1).text());
            assertFalse(say(lane, STEVE, "너무 빨리", Stage.FREE_TALK), "One model answer per player every moment");
            assertTrue(say(lane, ALEX, "다른 사람", Stage.FREE_TALK)); reply(2);
            later(2_000); assertTrue(say(lane, STEVE, "셋", Stage.FREE_TALK)); reply(3);
            assertEquals(List.of("minecraft-chat-dialogue-failed", "minecraft-chat-dialogue-failed", "minecraft-chat-dialogue-failed"), codes);
            later(2_000); assertFalse(say(lane, STEVE, "넷", Stage.FREE_TALK), "After three failures chat goes back to the rules for a minute");
            assertTrue(say(lane, STEVE, "장난하지 마", Stage.PERSONAL), "Settings never fall through to the game rules"); reply(4);
            later(60_000); assertTrue(say(lane, STEVE, "다섯", Stage.FREE_TALK)); reply(5); assertEquals(4, models.get());
        }
    }
    @Test void ruleAnswersAndAnnouncementsEnterThePublicLogAndARuleAnswerOpensAFollowUp() throws Exception {
        try (var store = store(); var lane = lane(store, answering("대답"))) {
            lane.said("나무를 캐러 갈게요.", null, ""); lane.said("돌을 캐러 갈게요.", null, "");
            assertEquals(List.of("돌을 캐러 갈게요."), lane.recent().stream().map(ConversationTurns.Line::text).toList());
            assertFalse(lane.following(STEVE));
            lane.said("저는 지금 돌을 캐는 중이에요.", STEVE, "Bot 뭐 해?");
            assertTrue(lane.following(STEVE));
            assertEquals(List.of("Herry>게임 채팅:돌을 캐러 갈게요.", "게임 채팅 Steve>Herry:Bot 뭐 해?", "Herry>게임 채팅 Steve:저는 지금 돌을 캐는 중이에요."),
                    lane.recent().stream().map(l -> l.speaker() + ">" + l.target() + ":" + l.text()).toList());
            later(2_000); assertTrue(say(lane, ALEX, "나도 알려 줘", Stage.FREE_TALK)); reply(1);
            var request = requests.getLast();
            assertEquals("나도 알려 줘", DialogueContext.currentInput(request));
            assertTrue(request.context().stream().anyMatch(l -> l.speaker().equals("게임 채팅 Steve") && l.text().equals("Bot 뭐 해?")), "Other players' public chat is context");
            assertEquals(1, request.context().stream().filter(l -> l.speaker().equals(ALEX.id())).count(), "The player's own line appears once, under their id");
            for (int index = 0; index < 20; index++) lane.said("대답 " + index, STEVE, "질문 " + index);
            assertEquals(12, lane.recent().size()); later(600_001); assertTrue(lane.recent().isEmpty(), "The public log is short-lived");
        }
    }
    @Test void memoryRestoreAndCloseRefuseEverythingAndForgetTemporaryContext() throws Exception {
        try (var store = store(); var lane = lane(store, answering("대답"))) {
            assertTrue(say(lane, STEVE, "안녕", Stage.FREE_TALK)); reply(1);
            lane.maintenance(true);
            assertFalse(say(lane, ALEX, "안녕", Stage.FREE_TALK)); assertFalse(say(lane, ALEX, "장난하지 마", Stage.PERSONAL));
            assertFalse(lane.following(STEVE)); assertTrue(lane.recent().isEmpty());
            lane.maintenance(false); later(2_000);
            assertTrue(say(lane, STEVE, "다시 안녕", Stage.FREE_TALK)); reply(2);
            assertEquals(1, requests.getLast().context().size(), "Earlier context was dropped by the restore");
            lane.close(); lane.close();
            later(2_000); assertFalse(say(lane, STEVE, "닫힌 뒤", Stage.FREE_TALK)); assertFalse(lane.following(STEVE)); assertTrue(lane.recent().isEmpty());
        }
    }
    @Test void oversizedOrEmptyLinesAreRefusedAndLongAnswersAreCutAtASentenceEnd() throws Exception {
        try (var store = store(); var lane = lane(store, answering("대답"))) {
            assertFalse(say(lane, STEVE, "가".repeat(257), Stage.FREE_TALK)); assertFalse(lane.offer(STEVE, " ", Stage.FREE_TALK, line(STEVE, "  ")));
            assertFalse(lane.offer(STEVE, null, Stage.FREE_TALK, line(STEVE, "x"))); assertFalse(lane.offer(STEVE, "x", Stage.FREE_TALK, null));
            for (String target : List.of("", "a b"))
                assertThrows(IllegalArgumentException.class, () -> new MinecraftChatConversation(settings(), store, answering("x"), target, relay, codes::add, wall::get, monotonic::get));
        }
        String sentence = "가".repeat(99) + ". ";
        String cut = MinecraftChatConversation.chatLine(sentence.repeat(4));
        assertEquals((sentence + sentence).strip(), cut); assertTrue(cut.length() <= 240);
        assertEquals(240, MinecraftChatConversation.chatLine("가".repeat(500)).length());
        assertEquals("한 줄 두 줄", MinecraftChatConversation.chatLine(" 한 줄\n\n두   줄 "));
    }
}
