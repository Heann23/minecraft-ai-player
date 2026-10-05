package me.herry.minecraftAI.ai.policy;

import me.herry.minecraftAI.ai.observation.Observation;

/**
 * 관측을 보고 "지금 할 단기 목표"를 고르는 정책. 학습한 모델이 이 자리에 들어온다.
 *
 * 받는 것은 값만 담은 Observation 하나다. 월드, 가방, 기억, 진행 중인 계획에는 닿을 수 없으므로
 * 정책이 무엇을 답하든 그것만으로는 아무 일도 일어나지 않는다. 답을 실행할지는 부르는 쪽이 정한다.
 * 지금은 어디에서도 실행하지 않고, 기존 규칙(Teacher)의 판단과 견주어 보기만 한다 (ShadowRunner).
 *
 * 서버 메인 스레드에서 판단할 때마다 불리므로 빨리 답해야 한다. 오래 걸리거나 예외를 내는 정책은 꺼진다.
 */
public interface GoalPolicy {
    // 정책의 이름. 기록에 남는다.
    String id();

    // 모델이나 규칙이 바뀔 때마다 달라지는 값. 어느 판의 판단인지 기록에서 가린다.
    String version();

    // 고를 수 없으면 GoalPrediction.ABSTAIN 을 돌려준다. null 을 돌려주지 않는다.
    GoalPrediction predict(Observation observation);
}
