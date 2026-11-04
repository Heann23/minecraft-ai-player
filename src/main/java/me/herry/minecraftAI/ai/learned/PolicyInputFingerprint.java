package me.herry.minecraftAI.ai.learned;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Diagnostic identity of actual actor inputs, never a policy feature or a reward. */
public record PolicyInputFingerprint(String state, String stateOutsideObjective, String orderedCandidates) {
    public static PolicyInputFingerprint of(PolicyFrame frame) {
        MessageDigest state = digest(), outside = digest(), candidates = digest();
        ByteBuffer scratch = ByteBuffer.allocate(8);
        double[] observed = frame.state();
        for (int i = 0; i < observed.length; i++) {
            number(state, observed[i], scratch);
            if (i < 208 || i >= 232) number(outside, observed[i], scratch);
        }
        candidates.update(ByteBuffer.allocate(4).putInt(frame.options().size()).array());
        for (var option : frame.options()) {
            // Order, exact execution grouping and features all affect categorical inference.
            string(candidates, option.id()); string(candidates, option.inputId());
            for (double feature : option.features()) number(candidates, feature, scratch);
        }
        return new PolicyInputFingerprint(hex(state), hex(outside), hex(candidates));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void number(MessageDigest digest, double value, ByteBuffer scratch) {
        scratch.putLong(0, Double.doubleToLongBits(value)); digest.update(scratch.array());
    }
    private static void string(MessageDigest digest, String value) {
        if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private static String hex(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }
}
