package me.herry.minecraftAI.ai.primitive.control;

import java.util.Objects;

/** One command's execution result together with the actual observed state change. */
public record PrimitiveTransition(int index, PrimitiveCommand command, PrimitiveSnapshot before,
                                  PrimitiveSnapshot after, String status, String reason,
                                  OutcomeSignals signals) {
    public PrimitiveTransition {
        ContractValues.range(index, 0, PrimitiveProgram.MAX_STEPS - 1, "index");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        status = ContractValues.text(status, "status", 32);
        Objects.requireNonNull(reason, "reason");
        if (reason.length() > 512) throw new IllegalArgumentException("reason has at most 512 characters");
        Objects.requireNonNull(signals, "signals");
    }
}
