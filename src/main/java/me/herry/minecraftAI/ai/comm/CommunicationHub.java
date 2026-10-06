package me.herry.minecraftAI.ai.comm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * AI 와 사람 사이의 모든 말이 지나가는 곳. 인게임 채팅과 Discord 가 같은 경로를 쓴다.
 *
 * <pre>
 * 들어오는 말: 채널 어댑터 -> receive() -> LanguageInterpreter -> Participant(AI) -> 대답
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
        return true;
    }

    /**
     * 사람의 말을 받아서, 그 말을 들은 AI 가 대답하게 한다.
     * 직접 한 말이 아니면 본문에 이름이 불린 AI 만 반응한다.
     *
     * @return 대답한 AI 의 수
     */
    public int receive(IncomingMessage message) {
        if (!enabled || participants.isEmpty()) return 0;
        String lower = message.text().toLowerCase(Locale.ROOT);
        int answered = 0;
        // 대답하는 도중에 AI 가 멈추거나 제거될 수 있으므로 복사본을 돈다.
        for (Participant participant : List.copyOf(participants.values())) {
            String name = participant.name().toLowerCase(Locale.ROOT);
            boolean named = lower.contains(name);
            if (!named && !message.direct()) continue;

            // 이름 안의 글자를 낱말로 잘못 읽지 않도록 이름을 빼고 해석한다.
            IncomingMessage body = named ? message.withText(lower.replace(name, " ")) : message;
            Intent intent = interpreter.interpret(body);
            List<String> lines = participant.respond(message, intent == null ? Intent.NONE : intent);
            if (lines.isEmpty()) continue;
            answered++;
            for (String line : lines) reply(OutgoingMessage.reply(participant.name(), line, message));
        }
        return answered;
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
