package me.herry.minecraftAI.ai.policy;

import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.observation.Observation;

/**
 * "하던 일을 계속한다"고만 답하는 정책. 학습한 것이 아니다.
 *
 * 쓰임은 둘이다. 학습한 정책이 없어도 후보 정책을 같이 돌리는 길(Shadow)이 실제 서버에서 되는지 확인할 수 있고,
 * 나중에 학습한 정책을 평가할 때의 바닥이 된다. 목표는 자주 바뀌지 않으므로 이것만으로도 꽤 많이 맞는다.
 * 학습한 정책이 이보다 못 맞으면 배운 것이 없다는 뜻이다.
 */
public final class RepeatLastGoalPolicy implements GoalPolicy {
    public static final String ID = "repeat-last";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public GoalPrediction predict(Observation observation) {
        try {
            return GoalPrediction.of(GoalType.valueOf(observation.task().goal()));
        } catch (IllegalArgumentException e) {
            // 모르는 이름의 목표를 하고 있었다면 (다른 판의 기록 등) 답하지 않는다.
            return GoalPrediction.ABSTAIN;
        }
    }
}
