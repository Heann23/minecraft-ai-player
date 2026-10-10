package me.herry.minecraftAI.ai.primitive.control;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Program completion and task attainment are reported independently. */
public record PrimitiveResult(UUID programId, String status, String reason, boolean objectiveReached,
                              List<PrimitiveTransition> transitions, PrimitiveSnapshot finalState) {
    public PrimitiveResult {
        Objects.requireNonNull(programId, "programId");
        status = ContractValues.text(status, "status", 32);
        Objects.requireNonNull(reason, "reason");
        if (reason.length() > 512) throw new IllegalArgumentException("reason has at most 512 characters");
        transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        if (transitions.size() > PrimitiveProgram.MAX_STEPS) throw new IllegalArgumentException("too many transitions");
        Objects.requireNonNull(finalState, "finalState");
    }
}
