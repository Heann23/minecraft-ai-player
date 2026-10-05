package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.goal.GoalType;
import org.jetbrains.annotations.Nullable;

/**
 * 목표에 딸린 정보. 목표의 뜻(무엇을 얼마나)에는 들어가지 않는다.
 *
 * @param priority     클수록 먼저 한다. 기존 목표에서 온 것은 GoalSystem 의 점수가 정하므로 0 이다.
 * @param legacyOrigin 기존 GoalType 을 옮겨 적은 목표면 그 GoalType. 이런 목표는 그 GoalType 의 계획 함수로만 수행한다.
 *                     새로 만든 목표(사람의 부탁, 하위 목표 생성)면 null 이고, 맞는 스킬 중에서 골라 수행한다.
 */
public record GoalMetadata(int priority, @Nullable GoalType legacyOrigin) {
    public static final GoalMetadata NONE = new GoalMetadata(0, null);

    public static GoalMetadata from(GoalType legacy) {
        return new GoalMetadata(0, legacy);
    }
}
