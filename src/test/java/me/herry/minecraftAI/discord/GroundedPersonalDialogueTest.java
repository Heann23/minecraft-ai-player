package me.herry.minecraftAI.discord;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static me.herry.minecraftAI.discord.DiscordMemory.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundedPersonalDialogueTest {
    private DiscordSettings settings() { return DiscordSettings.read(Map.<String, Object>of("enabled", true,
            "guild-id", "12345678901234567", "voice-channel-id", "12345678901234568")::get); }
    private Subject subject() { return new Subject(settings().guildId(), "herry", "A"); }
    private Fact fact(Subject subject, Kind kind, String label, String value, long expiry, long revision) {
        return new Fact(new Key(subject, kind, "", label), value, Evidence.EXPLICIT, "source", 1000, expiry, revision);
    }
    private Fact name(String value) { return fact(subject(), Kind.NAME, "preferred", value, 0, 1); }
    private ResponsePipeline.Request request(String text, List<Fact> facts) {
        return new ResponsePipeline.Request(new ConversationTurns.Token(UUID.randomUUID(), 1, 1, "A"),
                List.of(new ConversationTurns.Line("A", "Herry", text, false, 1000)), facts);
    }
    private GroundedPersonalDialogue grounded() {
        return new GroundedPersonalDialogue(request -> { fail("personal name query reached inference"); return ""; }, settings(), () -> 2000);
    }
    @Test void ownConfirmedNameAnswersWithoutInferenceAndRetainsHonorific() throws Exception {
        for (String input : List.of("내 이름 뭐야?", "제 이름은 뭐예요?", "해리님, 제 이름 기억하세요?", "Herry, 내 호칭 알려줘", "내 이름 뭐였죠?"))
            assertEquals("저장한 호칭은 민수님이에요.", grounded().respond(request(input, List.of(name("민수님")))));
    }
    @Test void noConfirmedNameIsExplicitlyUnknownWithoutInferringFromSpeakerOrHistory() throws Exception {
        var original = request("제 이름 기억하세요?", List.of());
        var withHistory = new ResponsePipeline.Request(original.turn(), List.of(
                new ConversationTurns.Line("A", "민수", "민수라고 해", false, 900), original.context().getFirst()), List.of());
        assertTrue(grounded().respond(withHistory).contains("저장한 호칭이 없어요"));
        for (var facts : List.of(List.of(name("@everyone")), List.of(fact(subject(), Kind.NAME, "preferred", "민수", 1500, 1)),
                List.of(fact(subject(), Kind.NAME, "other-label", "민수", 0, 1)),
                List.of(fact(new Subject(settings().guildId(), "herry", "B"), Kind.NAME, "preferred", "민수", 0, 1)),
                List.of(fact(new Subject("another-guild", "herry", "A"), Kind.NAME, "preferred", "민수", 0, 1)),
                List.of(fact(new Subject(settings().guildId(), "another-character", "A"), Kind.NAME, "preferred", "민수", 0, 1))))
            assertTrue(grounded().respond(request("내 이름 뭐야?", facts)).contains("저장한 호칭이 없어요"));
    }
    @Test void latestNameWinsAndMalformedCorrectionDoesNotRevealPreviousName() throws Exception {
        assertTrue(grounded().respond(request("내 이름 뭐야?", List.of(name("민수"), fact(subject(), Kind.NAME, "preferred", "지훈", 0, 2)))).contains("지훈"));
        String answer = grounded().respond(request("내 이름 뭐야?", List.of(name("민수"), fact(subject(), Kind.NAME, "preferred", "bad value", 0, 2))));
        assertTrue(answer.contains("저장한 호칭이 없어요")); assertFalse(answer.contains("민수")); assertFalse(answer.contains("bad value"));
    }
    @Test void onlyOwnExplicitStyleChangesAnswerAndRefusalWins() throws Exception {
        var allowed = fact(subject(), Kind.SPEECH_AGREEMENT, "casual", "ALLOWED", 0, 2);
        assertTrue(grounded().respond(request("내 이름 뭐야?", List.of(name("민수"), allowed))).endsWith("야."));
        for (var style : List.of(fact(subject(), Kind.SPEECH_AGREEMENT, "casual", "REFUSED", 0, 3),
                fact(new Subject(settings().guildId(), "herry", "B"), Kind.SPEECH_AGREEMENT, "casual", "ALLOWED", 0, 3))) {
            List<Fact> facts = style.value().equals("REFUSED") ? List.of(name("민수"), allowed, style) : List.of(name("민수"), style);
            assertTrue(grounded().respond(request("내 이름 뭐야?", facts)).endsWith("예요."));
        }
    }
    @Test void quotesOtherPeopleBotIdentityAndOtherCurrentQuestionDelegate() throws Exception {
        var calls = new AtomicInteger();
        var model = new GroundedPersonalDialogue(request -> { calls.incrementAndGet(); return "일반 대화"; }, settings(), () -> 2000);
        for (String input : List.of("민수 이름 뭐야?", "해리님 이름 뭐예요?", "\"내 이름 뭐야\"라고 했어요", "내 이름은 예쁜가요?", "제 이름을 맞혀보세요", "오늘 뭐 할까요?"))
            assertEquals("일반 대화", model.respond(request(input, List.of(name("민수")))));
        var original = request("오늘 뭐 할까요?", List.of(name("민수")));
        assertEquals("일반 대화", model.respond(new ResponsePipeline.Request(original.turn(), List.of(
                new ConversationTurns.Line("A", "Herry", "내 이름 뭐야?", false, 900), original.context().getFirst(),
                new ConversationTurns.Line("B", "Herry", "내 이름 뭐야?", false, 1100)), original.memory())));
        assertEquals(7, calls.get());
    }
    @Test void cancellationAndCodeOwnedPermissionQuestionsCannotInferOrReturnStoredName() throws Exception {
        var current = new AtomicBoolean(false); var original = request("내 이름 뭐야?", List.of(name("민수")));
        var model = new GroundedPersonalDialogue(request -> { fail("unexpected inference"); return ""; }, settings(), () -> {
            current.set(false); return 2000;
        });
        var cancelable = new ResponsePipeline.Request(original.turn(), original.context(), original.memory(), false, current::get);
        assertThrows(java.io.IOException.class, () -> model.respond(cancelable));
        current.set(true); assertThrows(java.io.IOException.class, () -> model.respond(cancelable));
        assertThrows(IllegalArgumentException.class, () -> model.respond(new ResponsePipeline.Request(original.turn(), original.context(), original.memory(), true)));
    }
}
