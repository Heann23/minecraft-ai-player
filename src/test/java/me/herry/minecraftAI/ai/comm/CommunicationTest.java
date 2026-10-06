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

    // "장난 그만해" 는 말버릇을 고쳐 달라는 말이다. 하던 일을 멈추라는 명령으로 읽으면 AI 가 통째로 멈춘다.
    @Test
    void stopTeasingIsNotAStopCommand() {
        assertEquals(Intent.NONE, interpret("장난 그만해"));
        assertEquals(Intent.NONE, interpret("농담 좀 그만"));
        assertEquals(Intent.NONE, interpret("그만 놀려"));
        assertEquals(Intent.Type.STOP, interpret("하던 일 그만해").type());
        assertEquals(Intent.Type.STOP, interpret("그만").type());
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

    // --- 자유로운 대화(Dialogue) ---

    private static final class Talk implements CommunicationHub.Dialogue {
        String participant = "Bot";
        String followingSender;
        boolean takesPersonal;
        boolean takesFreeTalk;
        final List<String> offered = new ArrayList<>();
        final List<String> said = new ArrayList<>();

        @Override
        public String participant() {
            return participant;
        }

        @Override
        public String called(String text) {
            return text.startsWith("해리야") ? text.substring(3).strip() : null;
        }

        @Override
        public boolean following(IncomingMessage message) {
            return message.sender().equals(followingSender);
        }

        @Override
        public boolean offer(IncomingMessage message, String body, Stage stage) {
            offered.add(stage + ":" + body);
            return stage == Stage.PERSONAL ? takesPersonal : takesFreeTalk;
        }

        @Override
        public void said(String text, IncomingMessage replyTo) {
            said.add((replyTo == null ? "-" : replyTo.sender()) + ":" + text);
        }
    }

    // 잡담은 대화가 받고, 규칙으로 알아들은 게임 질문은 지금처럼 AI 가 바로 대답한다.
    @Test
    void freeTalkGoesToTheDialogueAndGameQuestionsKeepTheRuleAnswer() {
        CommunicationHub hub = hub(true);
        RecordingChannel channel = new RecordingChannel(MessageSource.IN_GAME);
        hub.addChannel(channel);
        Echo bot = new Echo("Bot");
        hub.join(bot);
        Talk talk = new Talk();
        talk.takesFreeTalk = true;
        hub.setDialogue(talk);

        assertEquals(1, hub.receive(IncomingMessage.inGame("Steve", "Bot 안녕", false)));
        assertEquals(List.of("PERSONAL:안녕", "FREE_TALK:안녕"), talk.offered);
        assertNull(bot.lastMessage);
        assertTrue(channel.sent.isEmpty());

        assertEquals(1, hub.receive(IncomingMessage.inGame("Steve", "BOT 지금 어디야?", false)));
        assertEquals(Intent.Type.ASK_LOCATION, bot.lastIntent.type());
        assertEquals(1, channel.sent.size());
        // 대화는 사람이 쓴 그대로에서 이름만 뺀 문장을 받고, 규칙 대답도 전해 듣는다.
        assertEquals("PERSONAL:지금 어디야?", talk.offered.getLast());
        assertEquals(3, talk.offered.size());
        assertEquals(List.of("Steve:answer from Bot"), talk.said);
    }

    // 문장 전체가 개인 설정이면 규칙보다 먼저 대화가 받는다. "장난 그만해" 가 정지 명령이 되지 않는다.
    @Test
    void personalSettingsAreTakenBeforeTheRules() {
        CommunicationHub hub = hub(true);
        hub.addChannel(new RecordingChannel(MessageSource.IN_GAME));
        Echo bot = new Echo("Bot");
        hub.join(bot);
        Talk talk = new Talk();
        talk.takesPersonal = true;
        hub.setDialogue(talk);

        assertEquals(1, hub.receive(IncomingMessage.inGame("Steve", "Bot 멈춰", true)));
        assertEquals(List.of("PERSONAL:멈춰"), talk.offered);
        assertNull(bot.lastIntent);
    }

    // "해리야" 로 부른 말은 대화를 맡은 AI 한 명에게만 간다. 대화가 받지 않으면 규칙 대답이 나간다.
    @Test
    void aCallWordReachesOnlyTheDialoguesParticipant() {
        CommunicationHub hub = hub(true);
        RecordingChannel channel = new RecordingChannel(MessageSource.IN_GAME);
        hub.addChannel(channel);
        Echo bot = new Echo("Bot");
        Echo other = new Echo("Other");
        hub.join(bot);
        hub.join(other);
        Talk talk = new Talk();
        hub.setDialogue(talk);

        assertEquals(1, hub.receive(IncomingMessage.inGame("Steve", "해리야 철 몇 개 있어?", false)));
        assertEquals(new Intent(Intent.Type.ASK_HAVE, Intent.Subject.IRON), bot.lastIntent);
        assertNull(other.lastMessage);

        // 잡담인데 대화가 받지 않으면(연결이 없거나 바쁠 때) 지금처럼 규칙 대답으로 돌아간다.
        assertEquals(1, hub.receive(IncomingMessage.inGame("Steve", "해리야 안녕", false)));
        assertEquals(Intent.NONE, bot.lastIntent);
        assertEquals(2, channel.sent.size());
        assertNull(other.lastMessage);

        // 대화를 맡은 AI 가 없으면 부르는 말도 통하지 않는다.
        talk.participant = "";
        assertEquals(0, hub.receive(IncomingMessage.inGame("Steve", "해리야 안녕", false)));
    }

    // 이름을 부르지 않은 이어지는 말: 질문과 잡담은 이어지지만 일을 시키지는 못한다.
    @Test
    void anUnnamedFollowUpNeverCommands() {
        CommunicationHub hub = hub(true);
        RecordingChannel channel = new RecordingChannel(MessageSource.IN_GAME);
        hub.addChannel(channel);
        Echo bot = new Echo("Bot");
        hub.join(bot);
        Talk talk = new Talk();
        talk.followingSender = "Steve";
        hub.setDialogue(talk);

        // 관리자의 "멈춰" 라도 이름이 없으면 명령이 아니다. 대화가 받지 않으면 아무 일도 없다.
        assertEquals(0, hub.receive(IncomingMessage.inGame("Steve", "멈춰", true)));
        assertNull(bot.lastIntent);
        assertTrue(channel.sent.isEmpty());
        assertEquals(List.of("PERSONAL:멈춰", "FREE_TALK:멈춰"), talk.offered);

        // 잡담을 대화가 받지 않아도 상태 문장으로 끼어들지 않는다.
        assertEquals(0, hub.receive(IncomingMessage.inGame("Steve", "진짜?", true)));
        assertTrue(channel.sent.isEmpty());

        // 게임 질문은 규칙으로 바로 대답한다.
        assertEquals(1, hub.receive(IncomingMessage.inGame("Steve", "철은 몇 개 있어?", true)));
        assertEquals(Intent.Type.ASK_HAVE, bot.lastIntent.type());
        assertEquals(1, channel.sent.size());

        // 대화 중이 아닌 사람의 이름 없는 말은 듣지 않는다.
        int before = talk.offered.size();
        assertEquals(0, hub.receive(IncomingMessage.inGame("Alex", "진짜?", true)));
        assertEquals(before, talk.offered.size());

        talk.takesFreeTalk = true;
        assertEquals(1, hub.receive(IncomingMessage.inGame("Steve", "집으로 돌아와", true)));
        assertEquals("FREE_TALK:집으로 돌아와", talk.offered.getLast());
        assertEquals(Intent.Type.ASK_HAVE, bot.lastIntent.type());
    }

    // 늦게 온 대답은 질문이 온 곳으로 나가고, 그 사이 AI 가 사라졌으면 나가지 않는다.
    @Test
    void lateDialogueAnswersAreDeliveredOnlyWhileTheAiIsStillThere() {
        CommunicationHub hub = hub(true);
        RecordingChannel inGame = new RecordingChannel(MessageSource.IN_GAME);
        RecordingChannel other = new RecordingChannel(MessageSource.DISCORD);
        hub.addChannel(inGame);
        hub.addChannel(other);
        hub.join(new Echo("Bot"));
        IncomingMessage question = IncomingMessage.inGame("Steve", "Bot 안녕", false);

        assertTrue(hub.deliver("bot", "반가워요.", question));
        assertEquals(1, inGame.sent.size());
        assertEquals("Bot", inGame.sent.getFirst().speaker());
        assertEquals("반가워요.", inGame.sent.getFirst().text());
        assertTrue(inGame.sent.getFirst().replyTo() == question);
        assertTrue(other.sent.isEmpty());

        assertFalse(hub.deliver("Bot", " ", question));
        assertFalse(hub.deliver("Bot", "대답", null));
        assertFalse(hub.deliver("Nobody", "대답", question));
        hub.leave("Bot");
        assertFalse(hub.deliver("Bot", "늦은 대답", question));
        assertFalse(hub(false).deliver("Bot", "대답", question));
        assertEquals(1, inGame.sent.size());
    }

    // 관리 명령(/ai say)과 대화가 없을 때의 채팅은 예전과 똑같이 규칙 대답이다.
    @Test
    void adminMessagesAndChatWithoutADialogueAreUnchanged() {
        CommunicationHub hub = hub(true);
        RecordingChannel channel = new RecordingChannel(MessageSource.IN_GAME);
        hub.addChannel(channel);
        Echo bot = new Echo("Bot");
        hub.join(bot);
        Talk talk = new Talk();
        talk.takesPersonal = true;
        talk.takesFreeTalk = true;
        hub.setDialogue(talk);

        assertEquals(1, hub.receive(new IncomingMessage(MessageSource.SYSTEM, "Admin", "Bot 안녕", false, true)));
        assertTrue(talk.offered.isEmpty());
        assertEquals(Intent.NONE, bot.lastIntent);

        hub.setDialogue(null);
        assertEquals(1, hub.receive(IncomingMessage.inGame("Steve", "Bot 안녕", false)));
        assertEquals(0, hub.receive(IncomingMessage.inGame("Steve", "해리야 안녕", false)));
        assertTrue(talk.offered.isEmpty());
        assertEquals(2, channel.sent.size());
    }

    // 대화를 맡은 AI 의 혼잣말(목표 알림 등)은 대화에도 알려 준다. 다른 AI 의 말은 알리지 않는다.
    @Test
    void announcementsOfTheDialoguesParticipantAreToldToIt() {
        CommunicationHub hub = hub(true);
        hub.addChannel(new RecordingChannel(MessageSource.IN_GAME));
        Talk talk = new Talk();
        hub.setDialogue(talk);

        assertTrue(hub.say("Bot", "나무를 캐러 갈게요.", true));
        assertTrue(hub.say("Other", "돌을 캐러 갈게요.", true));
        // 빈도 제한에 걸려 실제로 나가지 않은 말은 알리지 않는다.
        assertFalse(hub.say("Bot", "나무를 캐러 갈게요.", true));
        assertEquals(List.of("-:나무를 캐러 갈게요."), talk.said);
    }
}
