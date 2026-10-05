package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.observation.Observation;

/**
 * 이루려는 상태. "무엇을 얼마나"를 값으로 적은 것이고, 어떻게 이룰지는 Skill 이 정한다.
 * 완료와 실패는 Observation 만 보고 판정한다. 행동이 시작됐다거나 로그에 성공이 찍혔다는 것으로는 판정하지 않는다.
 *
 * 구현은 값 객체(record)로 만든다. 같은 값이면 같은 목표다.
 */
public interface Goal {
    GoalCategory category();

    // 사람이 읽을 수 있는 한 줄 (로그와 기록에 쓴다)
    String describe();

    GoalMetadata metadata();

    // 이미 이룬 상태인지
    boolean isAchieved(Observation observation);

    // 지금 시작하거나 계속할 수 있는지 (선행 조건). 판단할 근거가 없으면 true 다.
    default boolean canAttempt(Observation observation) {
        return true;
    }

    // 더는 이룰 수 없게 됐는지
    default boolean isFailed(Observation observation) {
        return false;
    }
}
