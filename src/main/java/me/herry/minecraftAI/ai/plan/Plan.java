package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.goal.GoalType;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.StringJoiner;

/**
 * 하나의 목표를 위해 순서대로 실행할 행동 목록.
 */
public final class Plan {
    private final GoalType goal;
    private final List<Action> actions;
    private int index;

    public Plan(GoalType goal, List<Action> actions) {
        this.goal = goal;
        this.actions = List.copyOf(actions);
    }

    public GoalType getGoal() {
        return goal;
    }

    public @Nullable Action current() {
        return index < actions.size() ? actions.get(index) : null;
    }

    public void advance() {
        index++;
    }

    // 지금 실행 중인 행동이 계획에서 몇 번째인지 (0 부터). 끝난 계획이면 행동의 수와 같다.
    public int position() {
        return index;
    }

    public boolean isFinished() {
        return index >= actions.size();
    }

    // 실행 중인 행동을 중단시키고 계획을 끝낸다.
    public void cancel(AIPlayer ai) {
        Action action = current();
        if (action != null) action.cancel(ai);
        index = actions.size();
    }

    public String describe() {
        StringJoiner joiner = new StringJoiner(" -> ");
        for (Action action : actions) joiner.add(action.getName());
        return joiner.toString();
    }

    // 행동 목록에서 지금 실행 중인 것을 대괄호로 표시한다. 끝난 계획이면 표시가 없다.
    public String describeProgress() {
        StringJoiner joiner = new StringJoiner(" -> ");
        for (int i = 0; i < actions.size(); i++) {
            String name = actions.get(i).getName();
            joiner.add(i == index ? "[" + name + "]" : name);
        }
        return joiner.toString();
    }
}
