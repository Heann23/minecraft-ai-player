package me.herry.minecraftAI.discord;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordJoinGreetingsTest {
    @TempDir Path directory;
    private final AtomicLong now = new AtomicLong(1000);
    private final AtomicInteger models = new AtomicInteger();
    private final LinkedBlockingQueue<String> spoken = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<ResponsePipeline.Request> requests = new LinkedBlockingQueue<>();
    private DiscordSession session(SpeechRecognitionWorker.Recognizer recognizer, ResponsePipeline.Model model, ResponsePipeline.Voice voice) throws Exception {
        var settings = new DiscordSettings(true, "TEST_TOKEN", "12345678901234567", "12345678901234568", "herry", false, 60_000, 32,
                new DiscordMemoryStore.BackupPolicy(false, 600_000, 24, 7, 128L * 1024 * 1024));
        var store = new DiscordMemoryStore(directory, settings.backup(), now::get);
        return new DiscordSession(settings, store, recognizer, model, voice, code -> fail(code), now::get, now::get, false, VoiceIngress.Policy.defaults(), true);
    }
    private DiscordSession session() throws Exception {
        return session((pcm, language) -> new SpeechRecognitionWorker.Recognition("그럼 같이 놀아요", false),
                request -> { models.incrementAndGet(); requests.add(request); return "좋아요."; },
                text -> { spoken.add(text); return new byte[PcmAudio.FRAME_BYTES]; });
    }
    private void play(DiscordSession session, String expected) throws Exception {
        assertEquals(expected, spoken.poll(3, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); PcmPlayback.Frame frame;
        while ((frame = session.nextFrame()) == null && System.nanoTime() < deadline) Thread.sleep(2);
        assertNotNull(frame); assertTrue(session.submitted(frame));
    }
    private void speech(DiscordSession session) throws Exception {
        for (int i = 0; i < 5; i++) { session.audio("A", new byte[PcmAudio.FRAME_BYTES], true, false).get(3, TimeUnit.SECONDS); now.addAndGet(20); }
        now.addAndGet(500); session.tick().get(3, TimeUnit.SECONDS);
    }
    @Test void firstGreetingSkipsModelAndFullySubmittedGreetingEnablesFollowup() throws Exception {
        try (var session = session()) {
            now.addAndGet(5000); session.tick().get(3, TimeUnit.SECONDS); assertTrue(spoken.isEmpty()); assertEquals(0, models.get());
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS); now.addAndGet(2000); session.tick().get(3, TimeUnit.SECONDS);
            play(session, "안녕하세요! "); play(session, "해리예요. "); play(session, "같이 즐겁게 놀아요.");
            assertEquals(0, models.get());
            // Reply immediately after the last submitted frame, before the voice worker may wake.
            speech(session); var request = requests.poll(3, TimeUnit.SECONDS); assertNotNull(request);
            assertEquals(1, models.get());
            assertTrue(request.context().stream().anyMatch(line -> line.assistant() && line.text().equals("안녕하세요! 해리예요. 같이 즐겁게 놀아요.")));
            assertEquals(List.of("그럼 같이 놀아요"), request.context().stream().filter(line -> !line.assistant()).map(ConversationTurns.Line::text).toList());
            play(session, "좋아요.");
        }
    }
    @Test void interruptedGreetingDoesNotInventAHearableIntroductionOrOpenFollowup() throws Exception {
        var entered = new CountDownLatch(1); var release = new CompletableFuture<Void>();
        try (var session = session((pcm, language) -> new SpeechRecognitionWorker.Recognition("그럼 같이 놀아요", false),
                request -> { models.incrementAndGet(); return "unexpected"; }, text -> { entered.countDown(); release.join(); return new byte[PcmAudio.FRAME_BYTES]; })) {
            session.participants(Set.of("A", "B"), Map.of()).get(3, TimeUnit.SECONDS); now.addAndGet(2000); session.tick().get(3, TimeUnit.SECONDS);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 2; i++) { session.audio("B", new byte[PcmAudio.FRAME_BYTES], true, false).get(3, TimeUnit.SECONDS); now.addAndGet(20); }
            release.complete(null); speech(session);
            now.addAndGet(2000); session.tick().get(3, TimeUnit.SECONDS);
            assertEquals(0, models.get());
            session.quiet(true).get(3, TimeUnit.SECONDS); assertNull(session.nextFrame());
        } finally { release.complete(null); }
    }
    @Test void humanSpeechRecognitionAndDirectQuestionTakePriorityOverQueuedGreeting() throws Exception {
        var recognized = new CountDownLatch(1); var recognitionRelease = new CompletableFuture<Void>();
        var modelEntered = new CountDownLatch(1); var modelRelease = new CompletableFuture<Void>();
        try (var session = session((pcm, language) -> { recognized.countDown(); recognitionRelease.join(); return new SpeechRecognitionWorker.Recognition("해리야 질문 있어요", false); },
                request -> { models.incrementAndGet(); modelEntered.countDown(); modelRelease.join(); return "네, 말씀해 주세요."; },
                text -> { spoken.add(text); return new byte[PcmAudio.FRAME_BYTES]; })) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS); now.addAndGet(1500); speech(session);
            assertTrue(recognized.await(3, TimeUnit.SECONDS)); now.addAndGet(2500); session.tick().get(3, TimeUnit.SECONDS); assertTrue(spoken.isEmpty());
            recognitionRelease.complete(null); assertTrue(modelEntered.await(3, TimeUnit.SECONDS));
            session.tick().get(3, TimeUnit.SECONDS); assertTrue(spoken.isEmpty());
            modelRelease.complete(null); play(session, "네, 말씀해 주세요.");
            now.addAndGet(3000); session.tick().get(3, TimeUnit.SECONDS); assertTrue(spoken.isEmpty()); assertEquals(1, models.get());
        } finally { recognitionRelease.complete(null); modelRelease.complete(null); }
    }
}
