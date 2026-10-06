package me.herry.minecraftAI.discord;

/** Administrator diagnostics: states, ages and fixed codes only. Never ids, names, paths, tokens or any conversation text. */
final class DiscordDiagnostics {
    record Snapshot(String gateway, boolean voiceConnected, boolean listening, boolean connectWanted, int users, boolean chatTalk, boolean memoryFailure,
                    long lastBackupAt, long lastBackupFailedAt, long now, String counters) {}
    private DiscordDiagnostics() {}
    static String describe(Snapshot value) {
        String gateway = value.gateway() != null && value.gateway().matches("[A-Z_]{1,40}|없음") ? value.gateway() : "알 수 없음";
        return "진단(본문·ID·경로 없음)\n게이트웨이: " + gateway + " · 음성: " + (value.voiceConnected() ? "연결됨" : "연결 안 됨")
                + " · 수신: " + (value.listening() ? "켜짐" : "꺼짐") + " · 자동 접속 의도: " + (value.connectWanted() ? "예" : "아니오")
                + " · 참가자: " + Math.max(0, value.users()) + "명" + " · 게임 채팅 대화: " + (value.chatTalk() ? "연결됨" : "연결 안 됨")
                + "\n기억 저장: " + (value.memoryFailure() ? "확인 필요" : "정상") + " · 마지막 백업: " + age(value.lastBackupAt(), value.now(), "아직 없음")
                + " · 마지막 백업 실패: " + age(value.lastBackupFailedAt(), value.now(), "없음")
                + "\n재시작 이후 진단 횟수: " + value.counters();
    }
    static String age(long at, long now, String none) {
        if (at <= 0) return none;
        long minutes = Math.max(0, now - at) / 60_000;
        return minutes < 1 ? "방금" : minutes < 120 ? minutes + "분 전" : minutes / 60 + "시간 전";
    }
}
