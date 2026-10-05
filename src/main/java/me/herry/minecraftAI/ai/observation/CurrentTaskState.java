package me.herry.minecraftAI.ai.observation;

import java.util.Objects;

/**
 * 하고 있던 일.
 *
 * @param goal      단기 목표 (GoalType 의 이름)
 * @param goalSpec  그 목표를 범용 Goal 로 적은 것 (Goal.describe)
 * @param skill     계획을 만든 스킬의 이름. 계획이 없으면 빈 문자열
 * @param action    실행 중인 행동의 이름. 없으면 None
 * @param plan      계획의 행동 목록 (실행 중인 것은 대괄호)
 * @param escaping  갇힌 곳에서 길을 파서 빠져나오는 중인지
 * @param restCount 이 목표가 계속 실패해서 쉬게 된 횟수
 */
public record CurrentTaskState(
        String goal,
        String goalSpec,
        String skill,
        String action,
        String plan,
        boolean escaping,
        int restCount
) {
    public CurrentTaskState {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(goalSpec, "goalSpec");
        Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(plan, "plan");
    }
}
