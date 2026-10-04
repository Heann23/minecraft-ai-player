package me.herry.minecraftAI.ai;

import java.util.logging.Logger;

/**
 * AI 의 판단 과정을 서버 콘솔(logs/latest.log)에만 출력한다. 게임 채팅에는 보내지 않는다. 꺼져 있을 때는 아무것도 출력하지 않는다.
 */
public final class AIDebugger {
    private final Logger logger;
    private boolean enabled;

    public AIDebugger(Logger logger, boolean enabled) {
        this.logger = logger;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void log(String aiName, String message) {
        if (!enabled) return;
        logger.info("[AI:" + aiName + "] " + message);
    }
}
