package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.observation.Observation;

import java.util.Objects;

/**
 * 아직 "이루려는 상태"로 적지 못한 기존 목표를 Goal 로 다룰 수 있게 감싼 것.
 * 가방 정리, 작업대와 화로 챙기기, 떨어진 아이템 줍기처럼 그때그때의 뒷정리에 가까운 일이 여기에 해당한다.
 *
 * 완료 조건을 값으로 적지 못했으므로 isAchieved 는 늘 false 다. 이런 목표는 GoalSystem 이 더는 고르지 않는 것으로 끝난다.
 */
public record LegacyGoalWrapper(GoalType legacyType, GoalMetadata metadata) implements Goal {
    public LegacyGoalWrapper {
        Objects.requireNonNull(legacyType, "legacyType");
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.legacyOrigin() != legacyType) throw new IllegalArgumentException("metadata must name " + legacyType);
    }

    public LegacyGoalWrapper(GoalType legacyType) {
        this(legacyType, GoalMetadata.from(legacyType));
    }

    @Override
    public GoalCategory category() {
        return GoalCategory.LEGACY;
    }

    @Override
    public String describe() {
        return "Legacy(" + legacyType + ")";
    }

    @Override
    public boolean isAchieved(Observation observation) {
        return false;
    }
}
