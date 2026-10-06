package me.herry.minecraftAI.discord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Bounded RIFF PCM conversion; no codecs, temporary files, or audio-device access. */
public final class WaveAudio {
    private WaveAudio() {}
    public static byte[] mono16k(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > 16_000 * 2 * 30) throw new IllegalArgumentException("STT PCM bounds");
        ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        tag(out, "RIFF"); out.putInt(36 + pcm.length); tag(out, "WAVE"); tag(out, "fmt "); out.putInt(16);
        out.putShort((short) 1).putShort((short) 1).putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16);
        tag(out, "data"); out.putInt(pcm.length).put(pcm); return out.array();
    }
    /** Accept PCM16 mono/stereo at 8–48kHz and linearly interpolate to JDA's 48kHz stereo big endian. */
    public static byte[] discordPcm(byte[] wav) throws IOException {
        if (wav == null || wav.length < 44 || wav.length > 12_000_000) throw invalid();
        ByteBuffer input = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (!tag(input, 0, "RIFF") || !tag(input, 8, "WAVE") || Integer.toUnsignedLong(input.getInt(4)) != wav.length - 8L) throw invalid();
        int rate = 0, channels = 0, offset = -1, length = 0; boolean format = false;
        for (int cursor = 12; cursor < wav.length;) {
            if (cursor > wav.length - 8) throw invalid();
            long size = Integer.toUnsignedLong(input.getInt(cursor + 4)), next = cursor + 8L + size + (size & 1);
            if (next > wav.length || size > Integer.MAX_VALUE) throw invalid();
            if (tag(input, cursor, "fmt ")) {
                if (format || size < 16) throw invalid(); format = true;
                channels = Short.toUnsignedInt(input.getShort(cursor + 10)); rate = input.getInt(cursor + 12);
                if (input.getShort(cursor + 8) != 1 || input.getShort(cursor + 22) != 16 || (channels != 1 && channels != 2)
                        || rate < 8000 || rate > 48_000 || input.getInt(cursor + 16) != rate * channels * 2 || input.getShort(cursor + 20) != channels * 2) throw invalid();
            } else if (tag(input, cursor, "data")) {
                if (offset != -1) throw invalid(); offset = cursor + 8; length = (int) size;
            }
            cursor = (int) next;
        }
        if (!format || offset < 0 || length == 0 || length % (channels * 2) != 0) throw invalid();
        int frames = length / (channels * 2);
        if (frames > rate * 60) throw invalid();
        int outputFrames = (int) ((long) frames * 48_000 / rate);
        ByteBuffer out = ByteBuffer.allocate(outputFrames * 4).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < outputFrames; i++) {
            long position = (long) i * rate; int left = (int) (position / 48_000), right = Math.min(left + 1, frames - 1), fraction = (int) (position % 48_000);
            for (int channel = 0; channel < 2; channel++) {
                int source = Math.min(channel, channels - 1);
                int a = input.getShort(offset + (left * channels + source) * 2), b = input.getShort(offset + (right * channels + source) * 2);
                out.putShort((short) (a + (b - a) * (long) fraction / 48_000));
            }
        }
        return out.array();
    }
    private static void tag(ByteBuffer buffer, String text) { buffer.put(text.getBytes(StandardCharsets.US_ASCII)); }
    private static boolean tag(ByteBuffer buffer, int at, String text) {
        for (int i = 0; i < 4; i++) if (buffer.get(at + i) != text.charAt(i)) return false; return true;
    }
    private static IOException invalid() { return new IOException("local provider WAV format or bounds"); }
}
