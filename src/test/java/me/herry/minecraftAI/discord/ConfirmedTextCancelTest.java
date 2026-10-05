package me.herry.minecraftAI.discord;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedTextCancelTest {
    @Test void acceptsOnlyWholeExplicitAnswerCancellation() {
        for (String text : List.of("답변 취소해줘", "답변 중단해주세요", "방금 질문 취소해줘", "해리, 답변 그만해줘.", "Herry 답변 멈춰줘!", "지금 답변 멈춰줘",
                "진행 중인 답변을 중단해줘", "하던 답변 그만해요", "대답은 그만해줘", "응답 준비 취소해주세요", "텍스트 답변 중단해줘", "내 질문 취소해줘", "질문 취소해주세요"))
            assertTrue(ConfirmedTextCancel.requested(text), text);
    }
    @Test void rejectsAmbiguousQuestionedQuotedOrEmbeddedText() {
        for (String text : List.of("그만", "멈춰", "그만해줘", "멈춰줘", "잠깐", "장난 그만해줘", "게임 멈춰줘", "작업 취소해줘", "답변 취소해줄 수 있어?",
                "답변 취소해줘?", "답변을 취소해도 돼", "답변 취소해줘 그리고 집에 가자", "친구가 답변 취소해줘라고 했어", "\"답변 취소해줘\"라고 말해줘",
                "민수야 답변 취소해줘", "답변 취소", "답변", " "))
            assertFalse(ConfirmedTextCancel.requested(text), text);
        assertFalse(ConfirmedTextCancel.requested(null));
    }
    @Test void doesNotOverlapOtherConfirmedPrivateRequests() {
        for (String text : List.of("답변 취소해줘", "방금 질문 취소해줘")) {
            assertNull(ConfirmedTextPreference.read(text), text);
            assertNull(ConfirmedTextForget.read(text), text);
            assertFalse(ConfirmedTextReset.requested(text), text);
        }
    }
    @Test void replyStatesAcceptanceNotDeliveryAndKeepsMemory() {
        assertTrue(ConfirmedTextCancel.reply(true).contains("요청"));
        assertTrue(ConfirmedTextCancel.reply(true).contains("유지"));
        assertTrue(ConfirmedTextCancel.reply(false).contains("없어요"));
    }
}
