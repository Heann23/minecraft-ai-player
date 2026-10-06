package me.herry.minecraftAI.discord;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordGatewayReadinessTest {
    @Test void readyEventIsSufficientWithoutPollingTransientJdaStatus() throws Exception {
        var readiness = new DiscordGatewayReadiness();
        readiness.ready();
        assertTrue(readiness.await(0, TimeUnit.MILLISECONDS));
    }
    @Test void timeoutAndShutdownCannotPretendGatewayIsReady() throws Exception {
        var readiness = new DiscordGatewayReadiness();
        assertFalse(readiness.await(0, TimeUnit.MILLISECONDS));
        readiness.shutdown();
        assertFalse(readiness.await(0, TimeUnit.MILLISECONDS));
        readiness.ready();
        assertFalse(readiness.await(0, TimeUnit.MILLISECONDS));
    }
    @Test void shutdownAfterReadyBeforeAwaitStillRejectsStartup() throws Exception {
        var readiness = new DiscordGatewayReadiness();
        readiness.ready(); readiness.shutdown();
        assertFalse(readiness.await(0, TimeUnit.MILLISECONDS));
    }
}
