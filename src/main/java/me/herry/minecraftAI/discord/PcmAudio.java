package me.herry.minecraftAI.discord;

/** JDA PCM: 48kHz stereo signed 16-bit big endian, exactly 20ms per packet. */
public final class PcmAudio {
    public static final int FRAME_BYTES = 3840;
    public static final int FRAME_MILLIS = 20;
    private PcmAudio() {}
    public static void requireFrame(byte[] pcm) {
        if (pcm == null || pcm.length != FRAME_BYTES) throw new IllegalArgumentException("PCM frame size");
    }
    /** Normalized RMS energy; a fallback measurement, not a speech model or confidence score. */
    public static double rms(byte[] pcm) {
        requireFrame(pcm);
        double sum = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            double sample = signed(pcm, i) / 32768.0;
            sum += sample * sample;
        }
        return Math.sqrt(sum / (pcm.length / 2));
    }
    /** Box low-pass and 3:1 downsample, stereo to mono, 16kHz little endian for STT. */
    public static byte[] mono16k(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || pcm.length % 12 != 0 || pcm.length > FRAME_BYTES * 1500)
            throw new IllegalArgumentException("PCM recording bounds");
        byte[] mono = new byte[pcm.length / 6];
        for (int input = 0, output = 0; input < pcm.length; input += 12, output += 2) {
            int sum = 0;
            for (int i = 0; i < 12; i += 2) sum += signed(pcm, input + i);
            short sample = (short) (sum / 6);
            mono[output] = (byte) sample; mono[output + 1] = (byte) (sample >> 8);
        }
        return mono;
    }
    private static int signed(byte[] pcm, int offset) { return (short) ((pcm[offset] << 8) | (pcm[offset + 1] & 0xff)); }
}
