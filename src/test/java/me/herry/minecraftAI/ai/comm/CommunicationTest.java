package me.herry.minecraftAI.ai.comm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationTest {
    private final KeywordInterpreter interpreter = new KeywordInterpreter();
    private long tick;

    private Intent interpret(String text) {
        return interpreter.interpret(IncomingMessage.inGame("Steve", text, true));
    }

    // --- 규칙 기반 해석기 ---

    // 요구사항에 예시로 적힌 문장들
    @Test
    void understandsTheExampleSentences() {
        assertEquals(Intent.Type.GO_HOME, interpret("집으로 돌아가").type());
        assertEquals(new Intent(Intent.Type.GATHER, Intent.Subject.IRON), interpret("철 좀 구해와"));
        assertEquals(Intent.Type.ASK_STATUS, interpret("지금 뭐 하고 있어?").type());
        assertEquals(Intent.Type.ASK_REASON, interpret("왜 거기로 가고 있어?").type());
        assertEquals(new Intent(Intent.Type.ASK_HAVE, Intent.Subject.DIAMOND), interpret("다이아 찾았어?"));
        assertEquals(Intent.Type.UNSUPPORTED, interpret("오늘은 네더 준비부터 하자").type());
    }

    @Test
    void tellsQuestionsFromRequests() {
        assertEquals(Intent.Type.ASK_HAVE, interpret("철 몇 개 있어?").type());
        assertEquals(Intent.Type.GATHER, interpret("다이아 찾아와").type());
        assertEquals(new Intent(Intent.Type.GATHER, Intent.Subject.WOOD), interpret("나무 좀 캐와"));
        assertEquals(new Intent(Intent.Type.GATHER, Intent.Subject.FOOD), interpret("먹을 것 좀 구해 줘"));
        assertEquals(new Intent(Intent.Type.GATHER, Intent.Subject.STONE), interpret("돌 좀 캐"));
    }

    @Test
    void doesNotMistakeGoBackForStone() {
        // "돌아가" 의 "돌" 을 자원으로 읽으면 안 된다.
        assertEquals(Intent.Type.GO_HOME, interpret("집으로 돌아가").type());
        assertEquals(Intent.Type.GO_HOME, interpret("거점으로 복귀해").type());
        // 집에 대해 묻는 말은 귀환 명령이 아니다.
        assertFalse(interpret("집에 뭐가 있어?").type() == Intent.Type.GO_HOME);
    }

    @Test
    void understandsControlWords() {
        assertEquals(Intent.Type.STOP, interpret("멈춰").type());
        assertEquals(Intent.Type.RESUME, interpret("다시 움직여").type());
        assertEquals(Intent.Type.AUTONOMOUS, interpret("이제 알아서 해").type());
        assertEquals(Intent.Type.ASK_LOCATION, interpret("지금 어디야?").type());
    }

    @Test
    void unknownSentencesAreNotGuessed() {
        assertEquals(Intent.NONE, interpret("안녕"));
        assertEquals(Intent.NONE, interpret(""));
        assertEquals(Intent.NONE, interpret("오늘 날씨 좋다"));
    }

    // --- 허브 ---

    private static final class RecordingChannel implements ChatChannel {
        private final MessageSource source;
        final List<OutgoingMessage> sent = new ArrayList<>();

        RecordingChannel(MessageSource source) {
            this.source = source;
        }

        @Override
        public MessageSource source() {
            return source;
        }

        @Override
        public void send(OutgoingMessage message) {
            sent.add(message);
        }
    }

    private static final class Echo implements CommunicationHub.Participant {
        private final String name;
        IncomingMessage lastMessage;
        Intent lastIntent;

        Echo(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<String> respond(IncomingMessage message, Intent intent) {
            lastMessage = message;
            lastIntent = intent;
            return List.of("answer from " + name);
        }
    }

    private CommunicationHub hub(boolean enabled) {
        return new CommunicationHub(enabled, 100L, () -> tick);
    }

    @Test
    void idleChatterIsRateLimitedButUrgentIsNot() {
        CommunicationHub hub = hub(true);
        RecordingChannel channel = new RecordingChannel(MessageSource.IN_GAME);
        hub.addChannel(channel);

        assertTrue(hub.say("Bot", "나무를 캐러 갈게요.", false));
        tick = 50;
        assertFalse(hub.say("Bot", "돌을 캐러 갑니다.", false));
        assertTrue(hub.say("Bot", "위험해요!", true));
        tick = 200;
        assertTrue(hub.say("Bot", "돌을 캐러 갑니다.", false));
        assertEquals(3, channel.sent.size());
        assertNull(channel.sent.getFirst().replyTo());
    }

    @Test
    void sameLineIsNotRepeatedEvenWhenUrgent() {
        CommunicationHub hub = hub(true);
        RecordingChannel channel = new RecordingChannel(MessageSource.IN_GAME);
        hub.addChannel(channel);

        assertTrue(hub.say("Bot", "도와주세요!", true));
        tick = 300;
        assertFalse(hub.say("Bot", "도와주세요!", true));
        tick = 1500;
        assertTrue(hub.say("Bot", "도와주세요!", true));
    }

    @Test
    void disabledHubStaysSilent() {
        CommunicationHub hub = hub(false);
        RecordingChannel channel = new RecordingChannel(MessageSource.IN_GAME);
        hub.addChannel(channel);
        hub.join(new Echo("Bot"));

        assertFalse(hub.say("Bot", "안녕하세요", true));
        assertEquals(0, hub.receive(IncomingMessage.inGame("Steve", "Bot 뭐 해?", true)));
        assertTrue(channel.sent.isEmpty());
    }

    @Test
    void onlyTheNamedAiAnswers() {
        CommunicationHub hub = hub(true);
        RecordingChannel channel = new RecordingChannel(MessageSource.IN_GAME);
        hub.addChannel(channel);
        Echo first = new Echo("Alpha");
        Echo second = new Echo("Beta");
        hub.join(first);
        hub.join(second);

        // 이름이 불리지 않으면 아무도 대답하지 않는다.
        assertEquals(0, hub.receive(IncomingMessage.inGame("Steve", "지금 뭐 해?", true)));
        assertEquals(1, hub.receive(IncomingMessage.inGame("Steve", "beta 지금 뭐 해?", true)));

        assertNull(first.lastMessage);
        assertNotNull(second.lastMessage);
        assertEquals(Intent.Type.ASK_STATUS, second.lastIntent.type());
        assertEquals(1, channel.sent.size());
        assertEquals("Beta", channel.sent.getFirst().speaker());
        assertNotNull(channel.sent.getFirst().replyTo());
    }

    // AI 이름 안의 글자("Iron")가 낱말로 읽히면 안 된다.
    @Test
    void aiNameIsNotReadAsAKeyword() {
        CommunicationHub hub = hub(true);
        Echo bot = new Echo("Iron_Bot");
        hub.join(bot);
        hub.addChannel(new RecordingChannel(MessageSource.IN_GAME));

        hub.receive(IncomingMessage.inGame("Steve", "Iron_Bot 나무 좀 구해와", true));
        assertEquals(new Intent(Intent.Type.GATHER, Intent.Subject.WOOD), bot.lastIntent);
    }

    // 대답은 질문이 온 곳(인게임이면 인게임, Discord 면 Discord)으로 돌아간다.
    @Test
    void repliesGoBackToWhereTheQuestionCameFrom() {
        CommunicationHub hub = hub(true);
        RecordingChannel inGame = new RecordingChannel(MessageSource.IN_GAME);
        RecordingChannel discord = new RecordingChannel(MessageSource.DISCORD);
        hub.addChannel(inGame);
        hub.addChannel(discord);
        hub.join(new Echo("Bot"));

        hub.receive(new IncomingMessage(MessageSource.DISCORD, "Alex", "지금 뭐 해?", true, false));
        assertEquals(1, discord.sent.size());
        assertTrue(inGame.sent.isEmpty());

        // 혼잣말(목표 알림 등)은 모든 채널로 나간다.
        hub.say("Bot", "철을 찾으러 갈게요.", true);
        assertEquals(2, discord.sent.size());
        assertEquals(1, inGame.sent.size());
    }

    @Test
    void answersAreNeverRateLimited() {
        CommunicationHub hub = hub(true);
        RecordingChannel channel = new RecordingChannel(MessageSource.IN_GAME);
        hub.addChannel(channel);
        hub.join(new Echo("Bot"));

        // 사람이 같은 것을 연달아 물어도 매번 대답한다.
        hub.receive(IncomingMessage.inGame("Steve", "Bot 뭐 해?", true));
        hub.receive(IncomingMessage.inGame("Steve", "Bot 뭐 해?", true));
        assertEquals(2, channel.sent.size());
    }

    @Test
    void departedAiNoLongerAnswers() {
        CommunicationHub hub = hub(true);
        hub.addChannel(new RecordingChannel(MessageSource.IN_GAME));
        hub.join(new Echo("Bot"));
        hub.leave("Bot");

        assertEquals(0, hub.receive(IncomingMessage.inGame("Steve", "Bot 뭐 해?", true)));
    }

    // 해석기를 다른 것(LLM 등)으로 바꿔 끼울 수 있다.
    @Test
    void interpreterCanBeReplaced() {
        CommunicationHub hub = hub(true);
        hub.addChannel(new RecordingChannel(MessageSource.IN_GAME));
        Echo bot = new Echo("Bot");
        hub.join(bot);
        hub.setInterpreter(message -> Intent.of(Intent.Type.STOP));

        hub.receive(IncomingMessage.inGame("Steve", "Bot 아무 말이나", true));
        assertEquals(Intent.Type.STOP, bot.lastIntent.type());
    }
}
