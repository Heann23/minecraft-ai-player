package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SpeechRecognitionWorkerTest {
    private final AtomicLong now = new AtomicLong(1000);
    private VoiceIngress ingress() { var ingress = new VoiceIngress(VoiceIngress.Policy.defaults(), now::get); ingress.participants(Set.of("A")); return ingress; }
    private VoiceIngress.Utterance utterance(VoiceIngress ingress) {
        for (int i = 0; i < 5; i++) { ingress.frame("A", new byte[PcmAudio.FRAME_BYTES], true, false); now.addAndGet(20); }
        now.addAndGet(500); return ingress.tick().completed().getFirst();
    }
    @Test void recognitionKeepsUserRouteAndUsesKoreanMonoAudio() throws Exception {
        var ingress = ingress(); var input = utterance(ingress); var results = new java.util.ArrayList<SpeechRecognitionWorker.Result>();
        try (var worker = new SpeechRecognitionWorker(ingress, (pcm, language) -> {
            assertEquals("ko", language); assertEquals(5 * 640, pcm.length); return new SpeechRecognitionWorker.Recognition("해리 안녕", true);
        }, results::add, code -> fail(code), now::get, 15_000)) {
            worker.submit(input).get(3, TimeUnit.SECONDS); assertEquals(1, results.size()); assertEquals(input.route(), results.getFirst().route()); assertTrue(results.getFirst().recognition().reliableFinal());
        }
    }
    @Test void duplicateUtteranceRunsOnlyOnce() throws Exception {
        var ingress = ingress(); var input = utterance(ingress); var calls = new AtomicInteger();
        try (var worker = new SpeechRecognitionWorker(ingress, (pcm, language) -> { calls.incrementAndGet(); return new SpeechRecognitionWorker.Recognition("안녕", true); }, ignored -> {}, code -> fail(code), now::get, 15_000)) {
            worker.submit(input).get(3, TimeUnit.SECONDS); worker.submit(input).get(3, TimeUnit.SECONDS); assertEquals(1, calls.get());
        }
    }
    @Test void expiredUtteranceDoesNotCallRecognizer() throws Exception {
        var ingress = ingress(); var input = utterance(ingress); now.addAndGet(15_001);
        try (var worker = new SpeechRecognitionWorker(ingress, (pcm, language) -> { fail("expired input"); return null; }, ignored -> fail("expired result"), code -> fail(code), now::get, 15_000)) { worker.submit(input).get(3, TimeUnit.SECONDS); }
    }
    @Test void leavingDuringSttRejectsLateProviderResult() throws Exception {
        var ingress = ingress(); var input = utterance(ingress); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var worker = new SpeechRecognitionWorker(ingress, (pcm, language) -> { entered.countDown(); release.await(); return new SpeechRecognitionWorker.Recognition("옛 답변", true); }, ignored -> fail("late result"), code -> fail(code), now::get, 15_000)) {
            var task = worker.submit(input); assertTrue(entered.await(3, TimeUnit.SECONDS)); ingress.participants(Set.of()); release.countDown(); task.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }
    @Test void newerUtteranceRejectsEarlierRecognition() throws Exception {
        var ingress = ingress(); var input = utterance(ingress); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var results = new java.util.ArrayList<SpeechRecognitionWorker.Result>();
        try (var worker = new SpeechRecognitionWorker(ingress, (pcm, language) -> { entered.countDown(); release.await(); return new SpeechRecognitionWorker.Recognition("안녕", true); }, results::add, code -> fail(code), now::get, 15_000)) {
            var old = worker.submit(input); assertTrue(entered.await(3, TimeUnit.SECONDS)); var fresh = utterance(ingress); release.countDown(); old.get(3, TimeUnit.SECONDS); worker.submit(fresh).get(3, TimeUnit.SECONDS);
            assertEquals(1, results.size()); assertEquals(fresh.route(), results.getFirst().route());
        } finally { release.countDown(); }
    }
    @Test void limitedRecordingCannotBeTreatedAsReliableFinalInput() throws Exception {
        var ingress = ingress(); var original = utterance(ingress); var limited = new VoiceIngress.Utterance(original.route(), original.pcm(), original.endedAt(), true); var results = new java.util.ArrayList<SpeechRecognitionWorker.Result>();
        try (var worker = new SpeechRecognitionWorker(ingress, (pcm, language) -> new SpeechRecognitionWorker.Recognition("내 이름은 민수야", true), results::add, code -> fail(code), now::get, 15_000)) {
            worker.submit(limited).get(3, TimeUnit.SECONDS); assertFalse(results.getFirst().recognition().reliableFinal());
        }
    }
    @Test void closeInterruptsRecognitionAndRejectsNewInput() throws Exception {
        var ingress = ingress(); var input = utterance(ingress); var entered = new CountDownLatch(1); var ended = new CountDownLatch(1);
        var worker = new SpeechRecognitionWorker(ingress, (pcm, language) -> {
            entered.countDown(); try { new CountDownLatch(1).await(); return null; } finally { ended.countDown(); }
        }, ignored -> fail("closed result"), code -> fail(code), now::get, 15_000);
        try { worker.submit(input); assertTrue(entered.await(3, TimeUnit.SECONDS)); worker.close(); assertTrue(ended.await(3, TimeUnit.SECONDS)); worker.submit(input).get(3, TimeUnit.SECONDS); }
        finally { worker.close(); }
    }
    @Test void closeCancelsQueuedRecognitionFutures() throws Exception {
        var ingress = ingress(); var first = utterance(ingress); var entered = new CountDownLatch(1); var ended = new CountDownLatch(1);
        var worker = new SpeechRecognitionWorker(ingress, (pcm, language) -> {
            entered.countDown(); try { new CountDownLatch(1).await(); return null; } finally { ended.countDown(); }
        }, ignored -> fail("closed result"), code -> fail(code), now::get, 15_000);
        try {
            worker.submit(first); assertTrue(entered.await(3, TimeUnit.SECONDS)); var queued = worker.submit(utterance(ingress));
            worker.close(); assertTrue(ended.await(3, TimeUnit.SECONDS)); assertTrue(queued.isCancelled());
            assertThrows(java.util.concurrent.CancellationException.class, () -> queued.get(3, TimeUnit.SECONDS));
        } finally { worker.close(); }
    }
    @Test void supersededQueuedUtterancesCannotCrowdOutNewestCorrection() throws Exception {
        var ingress = ingress(); var first = utterance(ingress); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var results = new java.util.ArrayList<SpeechRecognitionWorker.Result>();
        try (var worker = new SpeechRecognitionWorker(ingress, (pcm, language) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); boolean finished = false;
                while (!finished) { try { release.await(); finished = true; } catch (InterruptedException ignored) { } }
            }
            return new SpeechRecognitionWorker.Recognition("정정한 발화", true);
        }, results::add, code -> fail(code), now::get, 15_000)) {
            worker.submit(first); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var second = worker.submit(utterance(ingress)); var third = worker.submit(utterance(ingress));
            var latest = utterance(ingress); var fourth = worker.submit(latest); assertTrue(second.isCancelled()); assertTrue(third.isCancelled());
            release.countDown(); fourth.get(3, TimeUnit.SECONDS); assertEquals(1, results.size()); assertEquals(latest.route(), results.getFirst().route());
        } finally { release.countDown(); }
    }
}
