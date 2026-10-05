package me.herry.minecraftAI.discord;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.herry.minecraftAI.ai.comm.GameStateSnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundedGameDialogueTest {
    private DiscordSettings settings() { return DiscordSettings.read(Map.<String, Object>of("enabled", true, "guild-id", "12345678901234567", "voice-channel-id", "12345678901234568")::get); }
    private GameStateSnapshot state(String lifecycle, String reason) {
        return new GameStateSnapshot("Bot", lifecycle, "MINE_STONE", "돌을 캐는 중", "BreakBlock", reason, "NORMAL", 3, 64, -7, 19.5, 17, Map.of("COBBLESTONE", 12, "DIAMOND", 2));
    }
    private ResponsePipeline.Request request(String text, List<DiscordMemory.Fact> facts) {
        return new ResponsePipeline.Request(new ConversationTurns.Token(UUID.randomUUID(), 1, 1, "A"),
                List.of(new ConversationTurns.Line("A", "Herry", text, false, 1000)), facts);
    }
    private GroundedGameDialogue grounded(GameStateSnapshot snapshot) {
        return new GroundedGameDialogue(request -> { fail("grounded question reached inference"); return ""; }, () -> new DiscordGameState.View(DiscordGameState.Code.FRESH, snapshot), settings(), () -> 2000);
    }
    private DiscordMemory.Fact style(String user, String guild, String value, long expiry) {
        return new DiscordMemory.Fact(new DiscordMemory.Key(new DiscordMemory.Subject(guild, "herry", user), DiscordMemory.Kind.SPEECH_AGREEMENT, "", "casual"), value, DiscordMemory.Evidence.EXPLICIT, "source", 1000, expiry, 1);
    }
    @Test void clearCurrentStatusNeverAllowsModelToAddInventedActivity() throws Exception {
        var model = grounded(state("STOPPED", ""));
        for (String input : List.of("해리님, 지금 뭐 하고 계세요?", "해리야 뭐해", "Herry, 지금 움직이고 있나요?", "현재 상태 알려주세요")) {
            String answer = model.respond(request(input, List.of()));
            assertTrue(answer.contains("자율 행동이 중지")); assertTrue(answer.contains("19.5")); assertTrue(answer.contains("17"));
            assertFalse(answer.contains("돌을 캐")); assertTrue(answer.length() < 400);
        }
    }
    @Test void locationAndExactItemCountsUseOnlyConfiguredSnapshot() throws Exception {
        var model = grounded(state("RUNNING", "돌이 필요해서요"));
        String where = model.respond(request("해리님, 어디 계세요?", List.of()));
        assertTrue(where.contains("오버월드")); assertTrue(where.contains("3, 64, -7"));
        assertTrue(model.respond(request("다이아몬드 몇 개 가지고 있나요?", List.of())).contains("2개"));
        assertTrue(model.respond(request("현재 석탄 몇개 있어?", List.of())).contains("0개"));
        assertTrue(model.respond(request("조약돌은 가지고 있어요?", List.of())).contains("12개"));
        assertTrue(model.respond(request("왜 그 일 하고 계세요?", List.of())).contains("돌이 필요해서요"));
    }
    @Test void everyUnavailableStateHasAnExplicitUnknownReplyAndNoInferenceOrZeroItemClaim() throws Exception {
        for (var code : DiscordGameState.Code.values()) {
            if (code == DiscordGameState.Code.FRESH) continue;
            var model = new GroundedGameDialogue(request -> { fail("unknown game question reached model"); return ""; }, () -> new DiscordGameState.View(code, null), settings(), () -> 2000);
            for (String question : List.of("지금 뭐하고 있어요?", "다이아몬드 몇개 있어?", "어디야?")) {
                String answer = model.respond(request(question, List.of()));
                assertTrue(answer.contains("확인할 수 없어요")); assertFalse(answer.contains("0개"));
            }
        }
    }
    @Test void previousItemNameOrFindingIsNotInventedFromCurrentCountsAndStoppedReasonIsUnknown() throws Exception {
        var model = grounded(state("STOPPED", "이유를 추측하지 않음"));
        String answer = model.respond(request("전에 발견한 돌 이름은 뭐였나요?", List.of()));
        assertTrue(answer.contains("확인할 수 없어요")); assertFalse(answer.contains("12개"));
        assertTrue(model.respond(request("왜 멈춰 있나요?", List.of())).contains("이유는 현재 자료에 없어요"));
    }
    @Test void onlyCurrentUtteranceCanSelectFactPathAndCommandsQuotesUserQuestionsUseDialogue() throws Exception {
        var calls = new AtomicInteger();
        var model = new GroundedGameDialogue(request -> { calls.incrementAndGet(); return "일반 대화"; }, () -> { fail("non-game dialogue read game facts"); return null; }, settings(), () -> 2000);
        for (String input : List.of("오늘 같이 뭐 할까요?", "왜 그런 말을 했나요?", "제가 지금 뭐 하는 것 같아요?", "민수님 지금 뭐해요?", "\"지금 뭐해\"라고 말했어요", "다이아몬드 캐줘", "해리님 안녕하세요", "석탄 있으면 집으로 가져와"))
            assertEquals("일반 대화", model.respond(request(input, List.of())));
        var token = request("", List.of()).turn();
        assertEquals("일반 대화", model.respond(new ResponsePipeline.Request(token, List.of(
                new ConversationTurns.Line("A", "Herry", "지금 뭐해요?", false, 1000),
                new ConversationTurns.Line("A", "Herry", "좋아하는 색은요?", false, 1100),
                new ConversationTurns.Line("B", "Herry", "지금 뭐해요?", false, 1200)), List.of())));
        assertEquals(9, calls.get());
    }
    @Test void confirmedStyleIsBoundToExactUserGuildCharacterAndExpiryAndRefusalWins() throws Exception {
        var model = grounded(state("STOPPED", "")); String guild = settings().guildId();
        assertTrue(model.respond(request("지금 뭐해요?", List.of(style("A", guild, "ALLOWED", 0)))).contains("상태야."));
        for (var facts : List.of(List.of(style("B", guild, "ALLOWED", 0)), List.of(style("A", "another-guild", "ALLOWED", 0)),
                List.of(style("A", guild, "ALLOWED", 1500)), List.of(style("A", guild, "ALLOWED", 0), style("A", guild, "REFUSED", 0))))
            assertTrue(model.respond(request("지금 뭐해요?", facts)).contains("상태예요."));
    }
    @Test void canceledAndCodeOwnedPermissionTurnsCannotReadStateOrInferAndRetirementAfterReadIsChecked() throws Exception {
        var current = new AtomicBoolean(false); var reads = new AtomicInteger();
        var model = new GroundedGameDialogue(request -> { fail("unexpected model work"); return ""; }, () -> {
            reads.incrementAndGet(); current.set(false); return new DiscordGameState.View(DiscordGameState.Code.FRESH, state("STOPPED", ""));
        }, settings(), () -> 2000);
        var original = request("지금 뭐해요?", List.of());
        var request = new ResponsePipeline.Request(original.turn(), original.context(), original.memory(), false, current::get);
        assertThrows(java.io.IOException.class, () -> model.respond(request)); assertEquals(0, reads.get());
        current.set(true); assertThrows(java.io.IOException.class, () -> model.respond(request)); assertEquals(1, reads.get());
        assertThrows(IllegalArgumentException.class, () -> model.respond(new ResponsePipeline.Request(original.turn(), original.context(), original.memory(), true)));
    }
}
