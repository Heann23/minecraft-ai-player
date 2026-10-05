package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.observation.Observation;

import java.util.Objects;

/**
 * 어떤 아이템을 정한 개수만큼 가진다. 캐든, 베든, 사냥하든, 구워 내든 방법은 묻지 않는다.
 *
 * 개수는 "지금 가지고 있는 양"이다 (가방, 손, 입은 것). 목표를 받은 뒤에 새로 얻은 양이 아니다.
 * 이미 그만큼 가지고 있으면 처음부터 이룬 목표다.
 *
 * @param item  Material 이름, 또는 ItemGroups 의 묶음 이름 (나무는 판자로 환산한 개수)
 * @param count 1 이상
 */
public record AcquireGoal(String item, int count, GoalMetadata metadata) implements Goal {
    public AcquireGoal {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(metadata, "metadata");
        if (count < 1) throw new IllegalArgumentException("count must be at least 1: " + count);
    }

    public AcquireGoal(String item, int count) {
        this(item, count, GoalMetadata.NONE);
    }

    @Override
    public GoalCategory category() {
        return GoalCategory.ACQUIRE;
    }

    @Override
    public String describe() {
        return "Acquire(" + item + " x" + count + ")";
    }

    @Override
    public boolean isAchieved(Observation observation) {
        return observation.inventory().count(item) >= count;
    }
}
