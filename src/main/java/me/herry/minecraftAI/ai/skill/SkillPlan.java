package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.action.Action;

import java.util.List;

/**
 * 스킬이 세운 계획.
 *
 * @param skill   계획을 세운 스킬의 이름. 세운 스킬이 없으면 빈 문자열
 * @param actions 순서대로 실행할 행동. 계획을 세우지 못했으면 빈 목록
 */
public record SkillPlan(String skill, List<Action> actions) {
    public static final SkillPlan NONE = new SkillPlan("", List.of());

    public SkillPlan {
        actions = List.copyOf(actions);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }
}
