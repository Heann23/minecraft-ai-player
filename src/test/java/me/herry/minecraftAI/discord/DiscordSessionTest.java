package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class DiscordSessionTest {
    @TempDir Path directory;
    private static final String GUILD = "12345678901234567";
    private final AtomicLong now = new AtomicLong(1000);
    private final BlockingQueue<String> transcriptions = new LinkedBlockingQueue<>();
    private final BlockingQueue<ResponsePipeline.Request> requests = new LinkedBlockingQueue<>();
    private final AtomicInteger models = new AtomicInteger();
    private DiscordSettings settings() {
        return new DiscordSettings(true, "DISCORD_TEST_TOKEN", GUILD, "12345678901234568", "herry", true,
                60_000, 32, new DiscordMemoryStore.BackupPolicy(false, 600_000, 24, 7, 128L * 1024 * 1024));
    }
    private DiscordMemoryStore store() throws Exception { return new DiscordMemoryStore(directory, settings().backup(), now::get); }
    private DiscordSession session(DiscordMemoryStore store) {
        return session(store, (pcm, language) -> {
            assertEquals("ko", language); assertEquals(5 * 640, pcm.length);
            return new SpeechRecognitionWorker.Recognition(transcriptions.take(), true);
        }, request -> { models.incrementAndGet(); requests.add(request); return "반가워요."; });
    }
    private DiscordSession session(DiscordMemoryStore store, SpeechRecognitionWorker.Recognizer recognizer, ResponsePipeline.Model model) {
        return new DiscordSession(settings(), store, recognizer, model, text -> new byte[PcmAudio.FRAME_BYTES],
                code -> fail(code), now::get, now::get, false);
    }
    private void speech(DiscordSession session, String user, String text) throws Exception {
        long previous = session.status().processedInputs(); capture(session, user, text);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (session.status().processedInputs() == previous && System.nanoTime() < end) Thread.sleep(2);
        assertEquals(previous + 1, session.status().processedInputs());
    }
    private void capture(DiscordSession session, String user, String text) throws Exception {
        transcriptions.add(text);
        for (int i = 0; i < 5; i++) { session.audio(user, new byte[PcmAudio.FRAME_BYTES], true, false).get(3, TimeUnit.SECONDS); now.addAndGet(20); }
        now.addAndGet(500); session.tick().get(3, TimeUnit.SECONDS);
    }
    private ResponsePipeline.Request request() throws Exception {
        var request = requests.poll(3, TimeUnit.SECONDS); assertNotNull(request); return request;
    }
    private PcmPlayback.Frame frame(DiscordSession session) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        PcmPlayback.Frame frame;
        while ((frame = session.nextFrame()) == null && System.nanoTime() < end) Thread.sleep(2);
        assertNotNull(frame); return frame;
    }
    private void play(DiscordSession session) throws Exception {
        var frame = frame(session); assertEquals(PcmAudio.FRAME_BYTES, frame.pcm().length); assertTrue(session.submitted(frame));
    }
    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    private static void ignoringInterrupt(CountDownLatch release) {
        boolean finished = false;
        while (!finished) { try { release.await(); finished = true; } catch (InterruptedException ignored) { } }
    }
    @Test void audioRoundTripFollowupCapturesNameAndOnlySubmittedTextEntersContext() throws Exception {
        var store = store();
        try (var session = session(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 안녕"); var first = request();
            assertEquals("A", first.turn().userId()); assertEquals(1, first.context().size()); play(session);
            speech(session, "A", "내 이름은 민수야"); var followup = request();
            assertEquals("민수", followup.memory().getFirst().value());
            assertTrue(followup.context().stream().anyMatch(line -> line.assistant() && line.text().equals("반가워요.")));
            play(session);
        }
        try (var reopened = store()) {
            assertEquals("민수", reopened.visible(new DiscordMemory.Subject(GUILD, "herry", "A"), Set.of("A")).join().getFirst().value());
        }
    }
    @Test void otherUsersConversationDoesNotCallModelOrLeakTheirPrivateMemory() throws Exception {
        var store = store(); var subject = new DiscordMemory.Subject(GUILD, "herry", "A");
        store.remember(new DiscordMemory.Key(subject, DiscordMemory.Kind.NAME, "", "preferred"), "민수",
                DiscordMemory.Evidence.EXPLICIT, "confirmed", 0).join();
        try (var session = session(store)) {
            session.participants(Set.of("A", "B"), Map.of("민수", "A", "지수", "B")).get(3, TimeUnit.SECONDS);
            speech(session, "A", "지수야 뭐 하세요?"); speech(session, "B", "해리야 안녕");
            var request = request(); assertEquals("B", request.turn().userId()); assertTrue(request.memory().isEmpty());
            assertEquals(1, models.get()); assertFalse(request.context().stream().anyMatch(line -> line.text().contains("뭐 하세요"))); play(session);
        }
    }
    @Test void participantSpeechOnsetStopsPlaybackBeforeRecognitionCompletes() throws Exception {
        var store = store();
        try (var session = session(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 안녕"); request(); var old = frame(session);
            session.audio("A", new byte[PcmAudio.FRAME_BYTES], true, false).get(3, TimeUnit.SECONDS); now.addAndGet(20);
            session.audio("A", new byte[PcmAudio.FRAME_BYTES], true, false).get(3, TimeUnit.SECONDS);
            assertFalse(session.submitted(old)); assertNull(session.nextFrame()); assertEquals(1, models.get());
        }
    }
    @Test void leaveAndRejoinRejectLateSttFromPriorMembership() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var store = store();
        try (var session = session(store, (pcm, language) -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); ignoringInterrupt(release); return new SpeechRecognitionWorker.Recognition("해리야 옛 입력", true); }
            return new SpeechRecognitionWorker.Recognition("해리야 새 입력", true);
        }, request -> { models.incrementAndGet(); requests.add(request); return "새 답변."; })) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            capture(session, "A", "unused"); await(entered);
            session.participants(Set.of(), Map.of()).get(3, TimeUnit.SECONDS);
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS); release.countDown();
            speech(session, "A", "unused"); var request = request();
            assertEquals(List.of("해리야 새 입력"), request.context().stream().map(ConversationTurns.Line::text).toList());
            assertEquals(1, models.get()); play(session);
        } finally { release.countDown(); }
    }
    @Test void forgetCancelsDelayedModelAndErasesItsPromptFromNextTurn() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var store = store();
        var subject = new DiscordMemory.Subject(GUILD, "herry", "A");
        store.remember(new DiscordMemory.Key(subject, DiscordMemory.Kind.NAME, "", "preferred"), "민수",
                DiscordMemory.Evidence.EXPLICIT, "confirmed", 0).join();
        try (var session = session(store, (pcm, language) -> new SpeechRecognitionWorker.Recognition(transcriptions.take(), true), request -> {
            if (models.incrementAndGet() == 1) { entered.countDown(); ignoringInterrupt(release); return "민수님 옛 답변."; }
            requests.add(request); return "새 답변.";
        })) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 민수 기억해"); await(entered); session.forget("A").get(3, TimeUnit.SECONDS);
            release.countDown(); speech(session, "A", "해리야 다시 안녕"); var request = request();
            assertTrue(request.memory().isEmpty()); assertEquals(1, request.context().size());
            assertEquals("해리야 다시 안녕", request.context().getFirst().text()); play(session);
        } finally { release.countDown(); }
    }
    @Test void quietClosesFollowupButExplicitCallCanResume() throws Exception {
        var store = store();
        try (var session = session(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 안녕"); request(); play(session); session.quiet(true).get(3, TimeUnit.SECONDS);
            speech(session, "A", "그 다음은?"); speech(session, "A", "해리야 다시 안녕");
            assertEquals("해리야 다시 안녕", request().context().getLast().text()); assertEquals(2, models.get()); play(session);
        }
    }
    @Test void explicitPunctuatedStopIsNotRecordedAsANewModelTurn() throws Exception {
        var store = store();
        try (var session = session(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 안녕"); request(); play(session);
            speech(session, "A", "해리야 그만!"); speech(session, "A", "그 다음은?");
            speech(session, "A", "해리야 다시 안녕"); var fresh = request();
            assertFalse(fresh.context().stream().anyMatch(line -> line.text().contains("그만") || line.text().equals("그 다음은?")));
            assertEquals(2, models.get()); play(session);
        }
    }
    @Test void newcomerDoesNotReceiveEarlierSharedTranscript() throws Exception {
        var store = store();
        try (var session = session(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 내 예전 고민"); request(); play(session);
            session.participants(Set.of("A", "B"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "B", "해리야 안녕"); var fresh = request();
            assertEquals(List.of("해리야 안녕"), fresh.context().stream().map(ConversationTurns.Line::text).toList()); play(session);
        }
    }
    @Test void closeInvalidatesPendingTransportAndRejectsNewInput() throws Exception {
        var store = store(); var session = session(store);
        try {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 안녕"); request(); var old = frame(session); session.close();
            assertTrue(session.status().closed()); assertEquals(0, session.status().users());
            assertFalse(session.submitted(old)); assertNull(session.nextFrame());
            assertThrows(java.util.concurrent.ExecutionException.class, () -> session.tick().get(3, TimeUnit.SECONDS));
        } finally { session.close(); }
        try (var reopened = store()) { assertFalse(reopened.status().closed()); }
    }
    @Test void invalidParticipantMetadataCannotPartiallyChangeMembership() throws Exception {
        var store = store();
        try (var session = session(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class, () -> session.participants(Set.of("B", "bad:id"), Map.of()));
            assertEquals(1, session.status().users()); speech(session, "A", "해리야 안녕"); assertEquals("A", request().turn().userId()); play(session);
        }
    }
    /** Production STT never marks a result reliable (LocalSpeechProviders), so voice behavior is tested with the real value. */
    private DiscordSession unreliable(DiscordMemoryStore store) {
        return session(store, (pcm, language) -> new SpeechRecognitionWorker.Recognition(transcriptions.take(), false),
                request -> { models.incrementAndGet(); requests.add(request); return "반가워요."; });
    }
    private List<DiscordMemory.Fact> facts(DiscordMemoryStore store, String user) {
        return store.visible(new DiscordMemory.Subject(GUILD, "herry", user), Set.of(user)).join();
    }
    @Test void unreliableVoiceStopJokesIsStoredAndAnsweredWithFixedTextWithoutModel() throws Exception {
        var store = store();
        try (var session = unreliable(store)) {
            session.participants(Set.of("A", "B"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 장난하지 마."); play(session);
            assertEquals(0, models.get());
            assertTrue(facts(store, "A").stream().anyMatch(fact -> fact.key().kind() == DiscordMemory.Kind.AVOID_JOKE && fact.value().equals("AVOID")));
            assertTrue(facts(store, "B").isEmpty());
        }
    }
    @Test void unreliableVoicePoliteRequestReplacesCasualConsentWithoutModel() throws Exception {
        var store = store(); var subject = new DiscordMemory.Subject(GUILD, "herry", "A");
        store.remember(new DiscordMemory.Key(subject, DiscordMemory.Kind.SPEECH_AGREEMENT, "", "casual"), "ALLOWED",
                DiscordMemory.Evidence.EXPLICIT, "confirmed", 0).join();
        assertTrue(DiscordPersonalSettings.casual(subject, facts(store, "A"), now.get()));
        try (var session = unreliable(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 존댓말로 해주세요"); play(session);
            assertEquals(0, models.get());
            assertFalse(DiscordPersonalSettings.casual(subject, facts(store, "A"), now.get()));
        }
    }
    @Test void unreliableVoiceNeverLoosensAndOtherwiseStillReachesTheModel() throws Exception {
        var store = store();
        try (var session = unreliable(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            for (String text : List.of("해리야 장난해도 돼요", "해리야 나한테 반말해도 돼요", "해리야 장난하지 마?", "해리야 친구가 장난하지 마 라고 했어")) {
                speech(session, "A", text); request(); play(session);
            }
            assertEquals(4, models.get()); assertTrue(facts(store, "A").isEmpty());
        }
    }
    @Test void voiceStopJokesFromSomeoneNotAddressingHerryIsIgnored() throws Exception {
        var store = store();
        try (var session = unreliable(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "장난하지 마");
            assertEquals(0, models.get()); assertNull(session.nextFrame()); assertTrue(facts(store, "A").isEmpty());
        }
    }
    @Test void unreliableVoiceNameIsEchoedAndStoredOnlyAfterTheSameUsersYes() throws Exception {
        var store = store();
        try (var session = unreliable(store)) {
            session.participants(Set.of("A", "B"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 안녕"); request(); play(session);
            speech(session, "A", "내 이름은 민수야"); play(session);
            assertEquals(1, models.get()); assertTrue(facts(store, "A").isEmpty());
            speech(session, "B", "응"); assertTrue(facts(store, "A").isEmpty());
            speech(session, "A", "응"); play(session);
            assertEquals(1, models.get());
            assertTrue(facts(store, "A").stream().anyMatch(fact -> fact.key().kind() == DiscordMemory.Kind.NAME && fact.value().equals("민수")));
            assertTrue(facts(store, "B").isEmpty());
        }
    }
    @Test void unreliableVoiceNoToTheEchoedNameStoresNothingAndTheQuestionIsSpent() throws Exception {
        var store = store();
        try (var session = unreliable(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 내 이름은 민수야"); play(session);
            speech(session, "A", "아니요"); play(session);
            assertEquals(0, models.get()); assertTrue(facts(store, "A").isEmpty());
            speech(session, "A", "응"); request(); play(session);
            assertEquals(1, models.get()); assertTrue(facts(store, "A").isEmpty());
        }
    }
    @Test void unreliableVoiceNameOfTheCharacterItselfIsNotEchoed() throws Exception {
        var store = store();
        try (var session = unreliable(store)) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리야 내 이름은 해리야"); request(); play(session);
            assertEquals(1, models.get()); assertTrue(facts(store, "A").isEmpty());
        }
    }
    @Test void directTextJokeBoundaryInterruptsOldVoiceAndAcknowledgesWithoutAnotherModelCall() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var store = store();
        try (var session = session(store, (pcm, language) -> new SpeechRecognitionWorker.Recognition(transcriptions.take(), true), request -> {
            models.incrementAndGet(); entered.countDown();
            try { new CountDownLatch(1).await(); return "폐기할 장난"; }
            catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
        })) {
            session.participants(Set.of("A"), Map.of()).get(3, TimeUnit.SECONDS);
            speech(session, "A", "해리님, 인사해 주세요"); await(entered);
            var reply = session.textReply("A", "해리님, 장난 그만해 주세요", "joke-stop").get(3, TimeUnit.SECONDS);
            assertEquals("미안해요. 장난은 멈추고 담백하게 이야기할게요.", reply.text());
            assertTrue(session.textCurrent(reply)); session.textSubmitted(reply); await(interrupted);
            assertNull(session.nextFrame()); assertEquals(1, models.get());
            assertTrue(session.personalSettings("A").get(3, TimeUnit.SECONDS).text().contains("장난 중단"));
        }
    }
}
