package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.observation.Observation;

import java.util.Objects;

/**
 * 블록을 써서 하는 일. 무엇을 "가지는" 것이 아니라 그 일을 "해 두는" 것이 목적인 목표다.
 */
public record InteractGoal(Interaction interaction, GoalMetadata metadata) implements Goal {
    public enum Interaction {
        // 거점의 침대에서 자서 밤을 넘긴다 (밤이 지나가면 이룬 것)
        SLEEP,
        // 알고 있는 전리품 상자를 열어 내용물을 챙긴다 (아는 상자가 남아 있지 않으면 이룬 것)
        LOOT_CHEST
    }

    public InteractGoal {
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(metadata, "metadata");
    }

    public InteractGoal(Interaction interaction) {
        this(interaction, GoalMetadata.NONE);
    }

    @Override
    public GoalCategory category() {
        return GoalCategory.INTERACT;
    }

    @Override
    public String describe() {
        return "Interact(" + interaction + ")";
    }

    @Override
    public boolean isAchieved(Observation observation) {
        return switch (interaction) {
            case SLEEP -> !observation.environment().night() && !observation.environment().thundering();
            case LOOT_CHEST -> !observation.memory().knowsLootChest();
        };
    }

    @Override
    public boolean canAttempt(Observation observation) {
        return switch (interaction) {
            case SLEEP -> observation.memory().canSleep();
            case LOOT_CHEST -> observation.memory().knowsLootChest();
        };
    }
}
