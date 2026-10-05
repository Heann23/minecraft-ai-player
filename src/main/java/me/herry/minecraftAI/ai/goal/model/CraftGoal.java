package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.observation.Observation;

import java.util.Objects;

/**
 * 어떤 아이템을 만들어서 가진다. AcquireGoal 과 달리 얻는 방법이 제작으로 정해져 있다.
 *
 * 개수는 AcquireGoal 과 같이 "지금 가지고 있는 양"이다. 작업대와 화로는 놓고 쓰는 것이라서,
 * 가방에 없어도 다시 찾아갈 만큼 가까이에 놓여 있으면 가진 것으로 친다.
 *
 * @param item  Material 이름
 * @param count 1 이상
 */
public record CraftGoal(String item, int count, GoalMetadata metadata) implements Goal {
    public static final String CRAFTING_TABLE = "CRAFTING_TABLE";
    public static final String FURNACE = "FURNACE";

    public CraftGoal {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(metadata, "metadata");
        if (count < 1) throw new IllegalArgumentException("count must be at least 1: " + count);
    }

    public CraftGoal(String item, int count) {
        this(item, count, GoalMetadata.NONE);
    }

    @Override
    public GoalCategory category() {
        return GoalCategory.CRAFT;
    }

    @Override
    public String describe() {
        return "Craft(" + item + " x" + count + ")";
    }

    @Override
    public boolean isAchieved(Observation observation) {
        if (observation.inventory().count(item) >= count) return true;
        if (count > 1) return false;
        if (item.equals(CRAFTING_TABLE)) return observation.progress().tableAvailable();
        return item.equals(FURNACE) && observation.progress().furnaceAvailable();
    }
}
