package me.herry.minecraftAI.ai.primitive.control;

import java.util.Objects;

/** Explicit precondition result used both for action masks and immediate execution checks. */
public record PrimitiveCheck(boolean allowed, String reason) {
    public PrimitiveCheck {
        Objects.requireNonNull(reason, "reason");
        if (!allowed && reason.isBlank()) throw new IllegalArgumentException("rejection needs a reason");
    }
    public static PrimitiveCheck allow() { return new PrimitiveCheck(true, ""); }
    public static PrimitiveCheck reject(String reason) { return new PrimitiveCheck(false, reason); }
}
