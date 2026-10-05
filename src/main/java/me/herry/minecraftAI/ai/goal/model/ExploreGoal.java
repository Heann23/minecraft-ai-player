package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.observation.Observation;

import java.util.Objects;

/**
 * 가 보지 않은 곳을 돌아본다. 달리 할 일이 없을 때 하는 일이라서 끝이 없다 (isAchieved 는 늘 false).
 * 다른 목표가 생기면 그쪽으로 바뀐다.
 */
public record ExploreGoal(GoalMetadata metadata) implements Goal {
    public ExploreGoal {
        Objects.requireNonNull(metadata, "metadata");
    }

    public ExploreGoal() {
        this(GoalMetadata.NONE);
    }

    @Override
    public GoalCategory category() {
        return GoalCategory.EXPLORE;
    }

    @Override
    public String describe() {
        return "Explore";
    }

    @Override
    public boolean isAchieved(Observation observation) {
        return false;
    }
}
