package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class VoiceIngressTest {
    private long now = 1000;
    private VoiceIngress ingress() { var ingress = new VoiceIngress(VoiceIngress.Policy.defaults(), () -> now); ingress.participants(Set.of("A", "B")); return ingress; }
    private byte[] frame(short sample) { byte[] pcm = new byte[PcmAudio.FRAME_BYTES]; for (int i = 0; i < pcm.length; i += 2) { pcm[i] = (byte) (sample >> 8); pcm[i + 1] = (byte) sample; } return pcm; }
    private void speak(VoiceIngress ingress, String user, int frames, boolean continued) { for (int i = 0; i < frames; i++) { ingress.frame(user, frame((short) 1000), true, continued); now += 20; } }
    @Test void constantPcmDownsamplesToCorrectEndianAndDuration() {
        byte[] mono = PcmAudio.mono16k(frame((short) -1000)); assertEquals(640, mono.length);
        for (int i = 0; i < mono.length; i += 2) assertEquals(-1000, (short) ((mono[i + 1] << 8) | (mono[i] & 0xff)));
        assertEquals(1000.0 / 32768, PcmAudio.rms(frame((short) 1000)), 0.000001);
    }
    @Test void oppositeStereoChannelsCancelInsteadOfOverflowing() {
        byte[] pcm = frame((short) 32767); for (int i = 2; i < pcm.length; i += 4) { pcm[i] = (byte) 0x80; pcm[i + 1] = 1; }
        assertArrayEquals(new byte[640], PcmAudio.mono16k(pcm));
    }
    @Test void malformedAndOversizedAudioIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PcmAudio.rms(new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> PcmAudio.mono16k(new byte[13]));
        assertThrows(IllegalArgumentException.class, () -> PcmAudio.mono16k(new byte[PcmAudio.FRAME_BYTES * 1501]));
    }
    @Test void capturesPerUserAndFinishesEvenWithoutSilencePackets() {
        try (var ingress = ingress()) {
            speak(ingress, "A", 5, false); speak(ingress, "B", 5, false); now += 500;
            var completed = ingress.tick().completed(); assertEquals(2, completed.size());
            assertEquals(Set.of("A", "B"), completed.stream().map(u -> u.route().userId()).collect(java.util.stream.Collectors.toSet()));
            for (var utterance : completed) { assertEquals(PcmAudio.FRAME_BYTES * 5, utterance.pcm().length); assertFalse(utterance.durationLimited()); }
        }
    }
    @Test void oneNoiseFrameCannotTriggerOnsetOrStt() {
        try (var ingress = ingress()) {
            assertTrue(ingress.frame("A", frame((short) 1), true, false).onsets().isEmpty()); now += 500;
            assertTrue(ingress.tick().completed().isEmpty());
        }
    }
    @Test void shortSpeechBelowMinimumIsDropped() {
        try (var ingress = ingress()) { speak(ingress, "A", 3, false); now += 500; assertTrue(ingress.tick().completed().isEmpty()); }
    }
    @Test void continuationHintAllowsKoreanHesitationWithoutFixedPauseSplit() {
        try (var ingress = ingress()) {
            speak(ingress, "A", 5, true); now += 500; assertTrue(ingress.tick().completed().isEmpty());
            speak(ingress, "A", 5, false); now += 500; var completed = ingress.tick().completed(); assertEquals(1, completed.size()); assertEquals(PcmAudio.FRAME_BYTES * 10, completed.getFirst().pcm().length);
        }
    }
    @Test void delayedNewPacketDoesNotJoinOldUtterance() {
        try (var ingress = ingress()) { speak(ingress, "A", 5, false); now += 500; assertEquals(1, ingress.frame("A", frame((short) 2), true, false).completed().size()); }
    }
    @Test void botsAndNonParticipantsDoNotAllocateCapture() {
        try (var ingress = ingress()) { assertTrue(ingress.frame("bot", new byte[1], true, false).onsets().isEmpty()); assertTrue(ingress.tick().completed().isEmpty()); }
    }
    @Test void frameAndUtteranceArraysAreDefensivelyCopied() {
        try (var ingress = ingress()) {
            byte[] pcm = frame((short) 1000); ingress.frame("A", pcm, true, false); java.util.Arrays.fill(pcm, (byte) 0);
            speak(ingress, "A", 4, false); now += 500; var utterance = ingress.tick().completed().getFirst(); byte[] returned = utterance.pcm();
            assertEquals(1000, (short) ((returned[0] << 8) | (returned[1] & 0xff))); java.util.Arrays.fill(returned, (byte) 0); assertNotEquals(0, utterance.pcm()[1]);
        }
    }
    @Test void maximumDurationIsBoundedAndMarkedForConfirmation() {
        var policy = new VoiceIngress.Policy(2, 0, 2, 5, 8, 500, 1000);
        try (var ingress = new VoiceIngress(policy, () -> now)) {
            ingress.participants(Set.of("A")); speak(ingress, "A", 7, false); var event = ingress.frame("A", frame((short) 1000), true, false);
            assertEquals(1, event.completed().size()); assertTrue(event.completed().getFirst().durationLimited()); assertEquals(PcmAudio.FRAME_BYTES * 8, event.completed().getFirst().pcm().length);
        }
    }
    @Test void newUtteranceInvalidatesOlderRecognitionAndRejoinInvalidatesRoutes() {
        try (var ingress = ingress()) {
            speak(ingress, "A", 5, false); now += 500; var old = ingress.tick().completed().getFirst().route(); assertTrue(ingress.valid(old));
            speak(ingress, "A", 2, false); assertFalse(ingress.valid(old));
            ingress.participants(Set.of("B")); ingress.participants(Set.of("A", "B")); assertFalse(ingress.valid(old));
        }
    }
    @Test void closeAndResetInvalidateAllRoutes() {
        var ingress = ingress(); speak(ingress, "A", 5, false); now += 500; var old = ingress.tick().completed().getFirst().route();
        ingress.reset(); assertFalse(ingress.valid(old)); ingress.close(); assertTrue(ingress.frame("A", frame((short) 1), true, false).onsets().isEmpty());
    }
}
