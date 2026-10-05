package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.observation.Observation;

import java.util.Objects;

/**
 * 구조물을 짓는다.
 */
public record BuildGoal(Structure structure, GoalMetadata metadata) implements Goal {
    public enum Structure {
        // 벽과 지붕이 다 지어진 집이 거점에 있다
        SHELTER,
        // 불이 붙은 네더 포탈이 있다 (짓는 행동은 아직 없다)
        NETHER_PORTAL
    }

    public BuildGoal {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(metadata, "metadata");
    }

    public BuildGoal(Structure structure) {
        this(structure, GoalMetadata.NONE);
    }

    @Override
    public GoalCategory category() {
        return GoalCategory.BUILD;
    }

    @Override
    public String describe() {
        return "Build(" + structure + ")";
    }

    @Override
    public boolean isAchieved(Observation observation) {
        return switch (structure) {
            case SHELTER -> observation.progress().shelterBuilt();
            case NETHER_PORTAL -> observation.progress().netherPortalBuilt();
        };
    }
}
