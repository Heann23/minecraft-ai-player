package me.herry.minecraftAI.discord;

import java.util.HashMap;
import java.util.Map;

/** Lets a fixed diagnostic code reach the console at most once per interval, so a flapping connection cannot flood the log. */
final class DiagnosticThrottle {
    static final int MAX_CODES = 16;
    private final long intervalMillis;
    private final Map<String, Long> last = new HashMap<>();
    DiagnosticThrottle(long intervalMillis) {
        if (intervalMillis < 1) throw new IllegalArgumentException("throttle interval");
        this.intervalMillis = intervalMillis;
    }
    /** Callers pass a monotonic clock. A code seen for the first time is always allowed. */
    synchronized boolean allow(String code, long nowMillis) {
        Long previous = last.get(code);
        if (previous != null && nowMillis - previous < intervalMillis) return false;
        if (previous == null && last.size() >= MAX_CODES) return false;
        last.put(code, nowMillis);
        return true;
    }
}
