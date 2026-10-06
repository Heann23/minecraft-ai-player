package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticThrottleTest {
    @Test void aCodeIsAllowedOncePerIntervalAndAgainAfterwards() {
        var throttle = new DiagnosticThrottle(30_000);
        assertTrue(throttle.allow("discord-gateway-disconnected", 1_000));
        assertFalse(throttle.allow("discord-gateway-disconnected", 1_001));
        assertFalse(throttle.allow("discord-gateway-disconnected", 30_999));
        assertTrue(throttle.allow("discord-gateway-disconnected", 31_000));
        assertFalse(throttle.allow("discord-gateway-disconnected", 31_001));
    }
    @Test void codesAreThrottledIndependently() {
        var throttle = new DiagnosticThrottle(30_000);
        assertTrue(throttle.allow("discord-gateway-disconnected", 5)); assertTrue(throttle.allow("discord-gateway-resumed", 6));
        assertFalse(throttle.allow("discord-gateway-disconnected", 7)); assertFalse(throttle.allow("discord-gateway-resumed", 8));
    }
    @Test void aMonotonicClockNeverReopensTheWindowEarly() {
        var throttle = new DiagnosticThrottle(1_000);
        assertTrue(throttle.allow("code-a", 10_000)); assertFalse(throttle.allow("code-a", 9_000));
    }
    @Test void theNumberOfRememberedCodesIsBounded() {
        var throttle = new DiagnosticThrottle(1_000);
        for (int index = 0; index < DiagnosticThrottle.MAX_CODES; index++) assertTrue(throttle.allow("code-" + index, 1));
        assertFalse(throttle.allow("one-more-code", 1)); assertTrue(throttle.allow("code-0", 5_000));
    }
    @Test void anIntervalBelowOneMillisecondIsRejected() { assertThrows(IllegalArgumentException.class, () -> new DiagnosticThrottle(0)); }
}
