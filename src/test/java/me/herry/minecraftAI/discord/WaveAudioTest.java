package me.herry.minecraftAI.discord;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class WaveAudioTest {
    @Test void sttWaveContainsExactMono16kSamplesAndValidHeader() throws Exception {
        byte[] pcm = {0x34, 0x12, 0, (byte) 0x80}; var wave = WaveAudio.mono16k(pcm);
        var header = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(40, header.getInt(4)); assertEquals(16_000, header.getInt(24)); assertEquals(32_000, header.getInt(28));
        assertEquals(1, header.getShort(22)); assertEquals(16, header.getShort(34)); assertEquals(pcm.length, header.getInt(40));
        assertArrayEquals(pcm, java.util.Arrays.copyOfRange(wave, 44, wave.length));
    }
    @Test void monoWaveBecomesStereoBigEndianWithLinearUpsampling() throws Exception {
        byte[] output = WaveAudio.discordPcm(WaveAudio.mono16k(new byte[]{0, 0, 0x30, 0x00}));
        assertEquals(24, output.length); var samples = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN);
        for (short expected : new short[]{0, 16, 32, 48, 48, 48}) { assertEquals(expected, samples.getShort()); assertEquals(expected, samples.getShort()); }
    }
    @Test void stereo48kWavePreservesDistinctChannels() throws Exception {
        byte[] wave = WaveAudio.mono16k(new byte[]{0x34, 0x12, (byte) 0xfe, (byte) 0xff});
        var format = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        format.putShort(22, (short) 2).putInt(24, 48_000).putInt(28, 192_000).putShort(32, (short) 4);
        assertArrayEquals(new byte[]{0x12, 0x34, (byte) 0xff, (byte) 0xfe}, WaveAudio.discordPcm(wave));
    }
    @Test void compressedUnsupportedOrInconsistentWaveIsRejected() {
        for (int offset : new int[]{0, 8, 20, 22, 24, 28, 32, 34, 40}) {
            byte[] wave = WaveAudio.mono16k(new byte[]{0, 0}); wave[offset] = 0x7f;
            assertThrows(IOException.class, () -> WaveAudio.discordPcm(wave), "offset " + offset);
        }
    }
    @Test void truncatedOversizedAndEmptyAudioCannotAllocateOutput() {
        assertThrows(IOException.class, () -> WaveAudio.discordPcm(new byte[43]));
        byte[] wave = WaveAudio.mono16k(new byte[]{0, 0});
        assertThrows(IOException.class, () -> WaveAudio.discordPcm(java.util.Arrays.copyOf(wave, wave.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> WaveAudio.mono16k(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> WaveAudio.mono16k(new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> WaveAudio.mono16k(new byte[960_002]));
    }
    @Test void oddUnknownChunkIsSkippedWithRequiredPadding() throws Exception {
        byte[] original = WaveAudio.mono16k(new byte[]{0x34, 0x12});
        ByteBuffer wave = ByteBuffer.allocate(original.length + 10).order(ByteOrder.LITTLE_ENDIAN);
        wave.put(original, 0, 12).put(new byte[]{'J','U','N','K'}).putInt(1).put((byte) 1).put((byte) 0).put(original, 12, original.length - 12);
        wave.putInt(4, wave.capacity() - 8);
        assertEquals(12, WaveAudio.discordPcm(wave.array()).length);
    }
}
