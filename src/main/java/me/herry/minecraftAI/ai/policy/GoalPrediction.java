package me.herry.minecraftAI.ai.policy;

import me.herry.minecraftAI.ai.goal.GoalType;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 정책의 답.
 *
 * @param goal   고른 목표. 고르지 못했으면 null
 * @param scores 목표별 점수나 확률. 정책이 낼 수 있는 것만 넣는다 (없으면 빈 맵)
 */
public record GoalPrediction(@Nullable GoalType goal, Map<GoalType, Double> scores) {
    public static final GoalPrediction ABSTAIN = new GoalPrediction(null, Map.of());

    public GoalPrediction {
        scores = Map.copyOf(scores);
    }

    public static GoalPrediction of(GoalType goal) {
        return new GoalPrediction(goal, Map.of());
    }
}
