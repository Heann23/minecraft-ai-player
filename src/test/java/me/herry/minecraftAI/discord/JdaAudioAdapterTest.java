package me.herry.minecraftAI.discord;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JdaAudioAdapterTest {
    @TempDir Path directory;
    private static final String USER = "12345678901234569";
    private static final class Fixture implements AutoCloseable {
        final AtomicLong now = new AtomicLong(1000);
        final AtomicInteger recognitions = new AtomicInteger();
        final BlockingQueue<ResponsePipeline.Request> requests = new LinkedBlockingQueue<>();
        final DiscordSession session;
        final JdaAudioAdapter audio;
        Fixture(Path directory) throws Exception {
            var settings = new DiscordSettings(true, "TEST_TOKEN", "12345678901234567", "12345678901234568", "herry", true, 60_000, 32,
                    new DiscordMemoryStore.BackupPolicy(false, 600_000, 24, 7, 128L * 1024 * 1024));
            var store = new DiscordMemoryStore(directory, settings.backup(), now::get);
            session = new DiscordSession(settings, store, (pcm, language) -> { recognitions.incrementAndGet(); return new SpeechRecognitionWorker.Recognition("해리야 안녕", false); },
                    request -> { requests.add(request); return "반가워요."; }, text -> new byte[PcmAudio.FRAME_BYTES], code -> {}, now::get, now::get, false);
            audio = new JdaAudioAdapter(session, 0.01, code -> fail(code));
            audio.connected(true); audio.participants(Set.of(USER), Map.of()).get(3, TimeUnit.SECONDS);
        }
        void speech(UserAudio packet) throws Exception {
            for (int frame = 0; frame < 5; frame++) { audio.handleUserAudio(packet); session.tick().get(3, TimeUnit.SECONDS); now.addAndGet(20); }
            now.addAndGet(501); session.tick().get(3, TimeUnit.SECONDS);
        }
        void waitFrame() throws Exception {
            assertNotNull(requests.poll(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!audio.canProvide() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(audio.canProvide());
        }
        @Override public void close() { audio.close(); session.close(); }
    }
    private UserAudio packet(String id, boolean bot, short amplitude) {
        User user = (User) Proxy.newProxyInstance(User.class.getClassLoader(), new Class<?>[]{User.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getId" -> id; case "isBot" -> bot; default -> throw new UnsupportedOperationException(method.getName());
        });
        short[] pcm = new short[PcmAudio.FRAME_BYTES / 2]; java.util.Arrays.fill(pcm, amplitude);
        return new UserAudio(user, pcm);
    }
    @Test void userPcmRoundTripHandsOffExactlyOneArrayBackedTwentyMillisecondFrame() throws Exception {
        try (var fixture = new Fixture(directory)) {
            assertTrue(fixture.audio.canReceiveUser()); assertFalse(fixture.audio.canReceiveCombined()); assertFalse(fixture.audio.isOpus());
            fixture.speech(packet(USER, false, (short) 12000)); fixture.waitFrame();
            var output = fixture.audio.provide20MsAudio(); assertNotNull(output); assertTrue(output.hasArray()); assertEquals(PcmAudio.FRAME_BYTES, output.remaining());
            assertNull(fixture.audio.provide20MsAudio()); assertFalse(fixture.audio.canProvide()); assertEquals(1, fixture.recognitions.get());
        }
    }
    @Test void botsOutsidersAndSilentNoiseNeverBecomeRecognizedSpeech() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.speech(packet(USER, true, (short) 12000)); fixture.speech(packet("12345678901234570", false, (short) 12000));
            fixture.speech(packet(USER, false, (short) 1)); assertNull(fixture.requests.poll(100, TimeUnit.MILLISECONDS)); assertEquals(0, fixture.recognitions.get());
        }
    }
    @Test void membershipChangeRejectsFrameAlreadyRequestedBeforeDeparture() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.speech(packet(USER, false, (short) 12000)); fixture.waitFrame();
            fixture.audio.participants(Set.of(), Map.of()).get(3, TimeUnit.SECONDS);
            assertNull(fixture.audio.provide20MsAudio()); assertFalse(fixture.audio.canProvide());
        }
    }
    @Test void unchangedMembershipWithNewAliasesDoesNotLoseInFlightFrame() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.speech(packet(USER, false, (short) 12000)); fixture.waitFrame();
            fixture.audio.participants(Set.of(USER), Map.of("민수", USER)).get(3, TimeUnit.SECONDS);
            assertNotNull(fixture.audio.provide20MsAudio());
        }
    }
    @Test void disconnectAndImmediateStopPreventOldAudioHandoff() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.speech(packet(USER, false, (short) 12000)); fixture.waitFrame(); fixture.audio.connected(false);
            assertNull(fixture.audio.provide20MsAudio()); assertFalse(fixture.audio.canReceiveUser());
            fixture.audio.close(); fixture.audio.connected(true); assertFalse(fixture.audio.canProvide()); assertFalse(fixture.audio.canReceiveUser());
        }
    }
    @Test void administrativeQuietBlocksExplicitCallsAndSurvivesReconnectUntilListen() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.audio.listening(false).get(3, TimeUnit.SECONDS); assertFalse(fixture.audio.canReceiveUser());
            fixture.speech(packet(USER, false, (short) 12000)); assertEquals(0, fixture.recognitions.get());
            fixture.audio.connected(false); fixture.audio.connected(true);
            fixture.audio.participants(Set.of(USER), Map.of()).get(3, TimeUnit.SECONDS); assertFalse(fixture.audio.canReceiveUser());
            fixture.audio.listening(true).get(3, TimeUnit.SECONDS); fixture.speech(packet(USER, false, (short) 12000)); fixture.waitFrame();
            assertNotNull(fixture.audio.provide20MsAudio()); assertEquals(1, fixture.recognitions.get());
        }
    }
}
