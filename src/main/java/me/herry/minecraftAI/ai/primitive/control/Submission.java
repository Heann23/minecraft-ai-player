package me.herry.minecraftAI.ai.primitive.control;

import java.util.Objects;

/** Admission of an explicit program request; acceptance is not a success result. */
public record Submission(boolean accepted, String reason) {
    public Submission {
        Objects.requireNonNull(reason, "reason");
        if (!accepted && reason.isBlank()) throw new IllegalArgumentException("rejection needs a reason");
    }
}
