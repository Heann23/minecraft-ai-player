package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordDiagnosticsTest {
    private static DiscordDiagnostics.Snapshot snapshot(String gateway, boolean voice, boolean listening, boolean wanted, boolean failure,
                                                        long backup, long backupFailed, long now) {
        return new DiscordDiagnostics.Snapshot(gateway, voice, listening, wanted, 2, voice, failure, backup, backupFailed, now, "discord-gateway-ready 1");
    }
    @Test void healthyStateIsShownWithAgesAndCounters() {
        long now = 10_000_000;
        String text = DiscordDiagnostics.describe(snapshot("CONNECTED", true, true, true, false, now - 12 * 60_000, 0, now));
        assertTrue(text.contains("게이트웨이: CONNECTED") && text.contains("음성: 연결됨") && text.contains("수신: 켜짐") && text.contains("자동 접속 의도: 예"), text);
        assertTrue(text.contains("참가자: 2명") && text.contains("게임 채팅 대화: 연결됨"), text);
        assertTrue(text.contains("기억 저장: 정상") && text.contains("마지막 백업: 12분 전") && text.contains("마지막 백업 실패: 없음"), text);
        assertTrue(text.endsWith("재시작 이후 진단 횟수: discord-gateway-ready 1"), text);
    }
    @Test void problemStatesAreShownPlainly() {
        long now = 10_000_000;
        String text = DiscordDiagnostics.describe(snapshot("DISCONNECTED", false, false, false, true, 0, now - 30_000, now));
        assertTrue(text.contains("음성: 연결 안 됨") && text.contains("수신: 꺼짐") && text.contains("자동 접속 의도: 아니오") && text.contains("게임 채팅 대화: 연결 안 됨"), text);
        assertTrue(text.contains("기억 저장: 확인 필요") && text.contains("마지막 백업: 아직 없음") && text.contains("마지막 백업 실패: 방금"), text);
    }
    @Test void agesUseMinutesThenHoursAndNeverGoNegative() {
        assertEquals("아직 없음", DiscordDiagnostics.age(0, 5, "아직 없음"));
        assertEquals("방금", DiscordDiagnostics.age(1000, 2000, "-"));
        assertEquals("119분 전", DiscordDiagnostics.age(1, 1 + 119 * 60_000L, "-"));
        assertEquals("2시간 전", DiscordDiagnostics.age(1, 1 + 120 * 60_000L, "-"));
        assertEquals("방금", DiscordDiagnostics.age(5_000, 1_000, "-"));
    }
    @Test void anUnexpectedGatewayTextIsNeverEchoed() {
        for (String gateway : new String[]{"connected", "C:\\secret\\path", "Gateway 12345678901234567", null, ""}) {
            String text = DiscordDiagnostics.describe(snapshot(gateway, true, true, true, false, 0, 0, 1));
            assertTrue(text.contains("게이트웨이: 알 수 없음"), text); assertFalse(text.contains("secret") || text.contains("12345678901234567"), text);
        }
        assertTrue(DiscordDiagnostics.describe(snapshot("없음", true, true, true, false, 0, 0, 1)).contains("게이트웨이: 없음"));
    }
}
