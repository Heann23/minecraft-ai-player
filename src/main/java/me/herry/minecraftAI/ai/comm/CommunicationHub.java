package me.herry.minecraftAI.ai.comm;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * AI 와 사람 사이의 모든 말이 지나가는 곳.
 *
 * <pre>
 * 들어오는 말: 채널 어댑터 -> receive() -> LanguageInterpreter -> Participant(AI) -> 대답
 *                                    └-> 게임 질문·부탁이 아닌 말 -> Dialogue(대화 모델) -> deliver()
 * 나가는 말:   AI -> say() -> 빈도 제한 -> 등록된 ChatChannel 들
 * </pre>
 *
 * AI 의 판단 코드는 이 클래스와 메시지 객체만 알고, Bukkit 채팅이나 Discord API 는 알지 못한다. 메인 스레드에서만 쓴다.
 */
public final class CommunicationHub {
    /**
     * 말을 듣고 대답하는 쪽 (AI 한 명).
     */
    public interface Participant {
        String name();

        // 대답할 문장들. 대답할 것이 없으면 빈 목록.
        List<String> respond(IncomingMessage message, Intent intent);
    }

    /**
     * AI 한 명의 자유로운 대화. 규칙으로 알아들은 게임 질문과 부탁은 지금처럼 Participant 가 바로 대답하고,
     * 그 밖의 말만 여기에 넘긴다. 대답은 틱보다 오래 걸리므로(언어 모델) 나중에 deliver() 로 돌아온다.
     * 메인 스레드에서만 불리며, 여기서 기다리는 일을 하면 안 된다. 대화가 받지 않으면 규칙 대답이 그대로 나간다.
     */
    public interface Dialogue {
        enum Stage {
            // 규칙보다 먼저: 문장 전체가 개인 설정(이름, 말투, 장난, 기억 삭제)일 때만 받는다.
            PERSONAL,
            // 규칙이 게임 질문이나 부탁을 찾지 못한 뒤
            FREE_TALK
        }

        // 이 대화로 말하는 AI 의 이름. 지금 아무도 없으면 "".
        String participant();

        // 맨 앞의 부르는 말("해리야 ...")을 뺀 문장. 그렇게 부른 말이 아니면 null.
        @Nullable String called(String text);

        // 아무 이름도 없는 말이, 보낸 사람이 이 AI 와 하던 대화에 이어지는 말인지.
        boolean following(IncomingMessage message);

        // 말을 받아서 나중에 deliver() 로 대답한다. false 면 규칙 대답에 맡긴다.
        boolean offer(IncomingMessage message, String body, Stage stage);

        // 이 AI 가 대화 밖에서 채팅에 한 말. 규칙 대답이면 replyTo 가 그 질문이고, 혼잣말이면 null.
        void said(String text, @Nullable IncomingMessage replyTo);
    }

    private static final long REPEAT_INTERVAL = 1200L;
    private static final int MAX_REMEMBERED_LINES = 32;

    private final boolean enabled;
    private final long minInterval;
    private final LongSupplier clock;
    private final List<ChatChannel> channels = new ArrayList<>();
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private final Map<String, Long> lastSpoke = new HashMap<>();
    // 말하는 쪽 별로, 어떤 말을 마지막으로 언제 했는지
    private final Map<String, Map<String, Long>> lastMessage = new HashMap<>();
    private LanguageInterpreter interpreter = new KeywordInterpreter();
    private @Nullable Dialogue dialogue;

    /**
     * @param minInterval 급하지 않은 혼잣말 사이의 최소 간격 (틱)
     * @param clock       현재 서버 틱
     */
    public CommunicationHub(boolean enabled, long minInterval, LongSupplier clock) {
        this.enabled = enabled;
        this.minInterval = minInterval;
        this.clock = clock;
    }

    public void addChannel(ChatChannel channel) {
        channels.add(channel);
    }

    public void removeChannel(ChatChannel channel) {
        channels.remove(channel);
    }

    // 규칙 기반 해석기를 다른 것(LLM 등)으로 바꾼다.
    public void setInterpreter(LanguageInterpreter interpreter) {
        this.interpreter = interpreter;
    }

    // 인게임 채팅의 자유로운 대화를 맡을 곳을 붙이거나(null 이면) 뗀다.
    public void setDialogue(@Nullable Dialogue dialogue) {
        this.dialogue = dialogue;
    }

    public void join(Participant participant) {
        participants.put(key(participant.name()), participant);
    }

    public void leave(String name) {
        participants.remove(key(name));
        lastSpoke.remove(name);
        lastMessage.remove(name);
    }

    /**
     * AI 가 스스로 하는 말을 내보낸다. 급하지 않은 말은 너무 자주 하지 않고, 급한 말이라도 같은 말을 연달아 되풀이하지 않는다.
     *
     * @return 실제로 내보냈으면 true
     */
    public boolean say(String speaker, String text, boolean urgent) {
        if (!enabled) return false;
        long now = clock.getAsLong();
        Long last = lastSpoke.get(speaker);
        if (!urgent && last != null && now - last < minInterval) return false;
        Map<String, Long> said = lastMessage.computeIfAbsent(speaker, name -> new HashMap<>());
        Long saidAt = said.get(text);
        if (saidAt != null && now - saidAt < REPEAT_INTERVAL) return false;
        if (said.size() > MAX_REMEMBERED_LINES) said.clear();
        lastSpoke.put(speaker, now);
        said.put(text, now);

        OutgoingMessage message = OutgoingMessage.announcement(speaker, text, urgent);
        for (ChatChannel channel : channels) channel.send(message);
        if (dialogue != null && key(speaker).equals(key(dialogue.participant()))) dialogue.said(text, null);
        return true;
    }

    /**
     * 사람의 말을 받아서, 그 말을 들은 AI 가 대답하게 한다.
     * 직접 한 말이 아니면 본문에 이름이 불린 AI 만 반응한다.
     *
     * @return 대답한(또는 대화가 대답하기로 한) AI 의 수
     */
    public int receive(IncomingMessage message) {
        if (!enabled || participants.isEmpty()) return 0;
        String lower = message.text().toLowerCase(Locale.ROOT);
        // 자유로운 대화는 인게임 채팅에만 붙는다. 관리 명령(/ai say)은 언제나 규칙 대답이다.
        Dialogue talk = message.source() == MessageSource.IN_GAME ? dialogue : null;
        String partner = talk == null ? "" : key(talk.participant());
        int answered = 0;
        // 대답하는 도중에 AI 가 멈추거나 제거될 수 있으므로 복사본을 돈다.
        for (Participant participant : List.copyOf(participants.values())) {
            String name = participant.name().toLowerCase(Locale.ROOT);
            boolean named = lower.contains(name);
            boolean talks = !partner.isEmpty() && partner.equals(name);
            String called = talks ? talk.called(message.text()) : null;
            // 이름이 없는 말이라도 이 AI 와 하던 대화에 이어지는 말이면 듣는다.
            boolean following = talks && !named && called == null && !message.direct() && talk.following(message);
            if (!named && called == null && !following && !message.direct()) continue;

            // 이름 안의 글자를 낱말로 잘못 읽지 않도록 이름을 빼고 해석한다.
            IncomingMessage body = called != null ? message.withText(called.toLowerCase(Locale.ROOT).replace(name, " "))
                    : named ? message.withText(lower.replace(name, " ")) : message;
            // 대화에는 사람이 쓴 그대로(대소문자·문장 부호)에서 부른 이름만 뺀 문장을 넘긴다.
            String plain = !talks ? "" : called != null ? called : named ? withoutName(message.text(), participant.name()) : message.text();
            if (talks && talk.offer(message, plain, Dialogue.Stage.PERSONAL)) {
                answered++;
                continue;
            }
            Intent intent = interpreter.interpret(body);
            if (intent == null) intent = Intent.NONE;
            // 이름을 부르지 않은 말로는 일을 시키지 못한다. 질문과 잡담만 이어진다.
            boolean chat = intent.type() == Intent.Type.NONE || (following && !question(intent));
            if (talks && chat && talk.offer(message, plain, Dialogue.Stage.FREE_TALK)) {
                answered++;
                continue;
            }
            // 이어지는 말을 대화가 받지 않았다면 그냥 둔다. 이름 없는 말에 상태 문장으로 끼어들지 않는다.
            if (following && chat) continue;
            List<String> lines = participant.respond(message, intent);
            if (lines.isEmpty()) continue;
            answered++;
            for (String line : lines) {
                reply(OutgoingMessage.reply(participant.name(), line, message));
                if (talks) talk.said(line, message);
            }
        }
        return answered;
    }

    /**
     * 대화가 질문보다 늦게 만든 대답을 내보낸다. 그 사이에 AI 가 사라졌으면 내보내지 않는다.
     * 질문이 온 곳으로 돌려보내는 방식은 다른 대답과 같다.
     *
     * @return 실제로 내보냈으면 true
     */
    public boolean deliver(String speaker, String text, IncomingMessage replyTo) {
        if (!enabled || speaker == null || text == null || text.isBlank() || replyTo == null) return false;
        Participant participant = participants.get(key(speaker));
        if (participant == null) return false;
        reply(OutgoingMessage.reply(participant.name(), text, replyTo));
        return true;
    }

    /** Exact target routing for external adapters. Original input identity is preserved in replies. */
    public int receiveTo(String targetName, IncomingMessage message) {
        return receiveTo(targetName, message, intent -> true);
    }

    /** A question-only adapter can reject a mutating intent before invoking the participant. */
    public int receiveTo(String targetName, IncomingMessage message, java.util.function.Predicate<Intent> allowed) {
        if (!enabled || targetName == null) return 0;
        Participant participant = participants.get(key(targetName));
        if (participant == null) return 0;
        String lower = message.text().toLowerCase(Locale.ROOT);
        String name = key(participant.name());
        IncomingMessage body = lower.contains(name) ? message.withText(lower.replace(name, " ")) : message;
        Intent intent = interpreter.interpret(body);
        if (intent == null) intent = Intent.NONE;
        if (!allowed.test(intent)) return 0;
        List<String> lines = participant.respond(message, intent);
        if (lines.isEmpty()) return 0;
        for (String line : lines) reply(OutgoingMessage.reply(participant.name(), line, message));
        return 1;
    }

    /** Main-thread read-only snapshot; names are immutable strings, never AI/world objects. */
    public List<String> participantNames() {
        return participants.values().stream().map(Participant::name).toList();
    }

    public boolean enabled() { return enabled; }

    private static String withoutName(String text, String name) {
        return Pattern.compile(Pattern.quote(name), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).replaceAll(" ").strip();
    }

    // 상태를 바꾸지 않고 대답만 하는 의도
    private static boolean question(Intent intent) {
        return switch (intent.type()) {
            case ASK_STATUS, ASK_REASON, ASK_HAVE, ASK_LOCATION -> true;
            default -> false;
        };
    }

    // 대답은 질문이 온 곳으로 돌려보낸다. 그런 채널이 없으면 모든 채널에 보낸다.
    private void reply(OutgoingMessage message) {
        boolean delivered = false;
        for (ChatChannel channel : channels) {
            if (message.replyTo() != null && channel.source() == message.replyTo().source()) {
                channel.send(message);
                delivered = true;
            }
        }
        if (delivered) return;
        for (ChatChannel channel : channels) channel.send(message);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
