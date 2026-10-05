package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.Goal;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 쓸 수 있는 스킬의 목록. 사람이 넣은 것만 들어간다. 실행 중에 스스로 늘어나지 않는다.
 */
public final class SkillRegistry {
    // 등록한 순서를 지킨다. 비용이 같으면 먼저 등록한 스킬을 먼저 고른다.
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    // 같은 이름의 스킬이 이미 있으면 그 자리에서 바꿔 넣는다.
    public void register(Skill skill) {
        Objects.requireNonNull(skill, "skill");
        skills.put(skill.name(), skill);
    }

    public @Nullable Skill get(String name) {
        return skills.get(name);
    }

    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    // 그 목표를 맡을 수 있는 스킬 전부 (지금 쓸 수 있는지는 따지지 않는다).
    public List<Skill> candidates(Goal goal) {
        List<Skill> result = new ArrayList<>();
        for (Skill skill : skills.values()) {
            if (skill.supports(goal)) result.add(skill);
        }
        return result;
    }

    // 그 목표를 맡을 수 있고 지금 쓸 수 있는 스킬을 싼 순서로.
    public List<Skill> executable(Goal goal, @Nullable Situation situation) {
        List<Skill> result = new ArrayList<>();
        for (Skill skill : skills.values()) {
            if (skill.supports(goal) && skill.canExecute(goal, situation)) result.add(skill);
        }
        result.sort(Comparator.comparingDouble(skill -> skill.estimateCost(goal, situation)));
        return result;
    }
}
