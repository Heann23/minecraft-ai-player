package me.herry.minecraftAI.discord;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCountersTest {
    @Test void emptyCountersSayNothingHappened() { assertEquals("없음", new DiagnosticCounters().describe()); }
    @Test void codesAreCountedAndListedMostFrequentFirstThenByName() {
        var counters = new DiagnosticCounters();
        for (int i = 0; i < 3; i++) counters.record("response-provider-failed");
        counters.record("discord-voice-connected"); counters.record("discord-connect-failed"); counters.record("discord-connect-failed");
        assertEquals("response-provider-failed 3 · discord-connect-failed 2 · discord-voice-connected 1", counters.describe());
    }
    @Test void onlyCodeShapedStringsAreKeptAnythingElseIsJustCountedAsOther() {
        var counters = new DiagnosticCounters();
        for (String text : java.util.List.of("C:\\secret\\path", "Secret Token abc", "UPPER-CASE", "ab", "해리 대화 내용", "a".repeat(80), ""))
            counters.record(text);
        counters.record(null); counters.record("discord-gateway-ready");
        String shown = counters.describe();
        assertEquals("discord-gateway-ready 1 · 기타 8", shown);
        assertFalse(shown.contains("secret") || shown.contains("Secret") || shown.contains("해리"));
    }
    @Test void distinctCodesAndTheShownListAreBounded() {
        var counters = new DiagnosticCounters();
        IntStream.range(0, DiagnosticCounters.MAX_CODES + 10).forEach(index -> counters.record("code-number-" + index));
        String shown = counters.describe();
        assertEquals(DiagnosticCounters.SHOWN, shown.split(" · ").length - 1, shown);
        assertTrue(shown.endsWith("기타 10"), shown);
    }
    @Test void concurrentRecordingDoesNotLoseCounts() throws Exception {
        var counters = new DiagnosticCounters();
        var threads = IntStream.range(0, 4).mapToObj(index -> new Thread(() -> { for (int i = 0; i < 500; i++) counters.record("discord-voice-connected"); })).toList();
        for (var thread : threads) thread.start();
        for (var thread : threads) thread.join();
        assertEquals("discord-voice-connected 2000", counters.describe());
    }
}
