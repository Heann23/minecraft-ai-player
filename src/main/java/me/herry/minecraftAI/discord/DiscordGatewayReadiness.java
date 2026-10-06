package me.herry.minecraftAI.discord;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ReadyEvent means caches are loaded even before JDA publishes its CONNECTED status. */
final class DiscordGatewayReadiness {
    private final CountDownLatch completed = new CountDownLatch(1);
    private boolean ready;
    private boolean stopped;

    synchronized void ready() {
        if (!stopped) ready = true;
        completed.countDown();
    }
    synchronized void shutdown() {
        stopped = true;
        ready = false;
        completed.countDown();
    }
    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        if (!completed.await(timeout, unit)) return false;
        synchronized (this) { return ready && !stopped; }
    }
}
