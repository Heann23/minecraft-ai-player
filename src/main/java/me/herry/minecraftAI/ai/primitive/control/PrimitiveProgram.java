package me.herry.minecraftAI.ai.primitive.control;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded explicit control request. Provenance identifies its source, not a Teacher label. */
public record PrimitiveProgram(int schemaVersion, UUID id, UUID worldId, long observedTick,
                               int maxTicks, String provenance, List<PrimitiveCommand> steps,
                               TaskObjective objective) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_STEPS = 32;
    public static final int MAX_TICKS = 2400;

    public PrimitiveProgram {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) throw new IllegalArgumentException("unsupported program schema");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(worldId, "worldId");
        if (observedTick < 0) throw new IllegalArgumentException("observedTick must be nonnegative");
        ContractValues.range(maxTicks, 1, MAX_TICKS, "maxTicks");
        provenance = ContractValues.text(provenance, "provenance", 128);
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        ContractValues.range(steps.size(), 1, MAX_STEPS, "step count");
        Objects.requireNonNull(objective, "objective");
    }
}
