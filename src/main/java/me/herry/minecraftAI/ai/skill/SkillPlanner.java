package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.Goal;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 목표를 받아서, 그것을 맡을 수 있는 스킬 중 지금 쓸 수 있는 것을 싼 순서로 시도해 계획을 세운다.
 */
public final class SkillPlanner {
    private final SkillRegistry registry;

    public SkillPlanner(SkillRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public SkillRegistry getRegistry() {
        return registry;
    }

    /**
     * @param situation 판단할 때의 상황. null 이면 상황을 따지지 않아도 되는 스킬(기존 목표를 그대로 수행하는 것)만 쓴다.
     * @return 계획을 세운 스킬과 그 행동들. 어느 스킬도 세우지 못했으면 SkillPlan.NONE
     */
    public SkillPlan plan(Goal goal, AIPlayer ai, @Nullable Situation situation) {
        for (Skill skill : registry.executable(goal, situation)) {
            List<Action> actions = skill.buildPlan(ai, goal, situation);
            if (!actions.isEmpty()) return new SkillPlan(skill.name(), actions);
        }
        return SkillPlan.NONE;
    }
}
