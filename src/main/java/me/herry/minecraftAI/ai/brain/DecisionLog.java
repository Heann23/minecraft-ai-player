package me.herry.minecraftAI.ai.brain;

import me.herry.minecraftAI.ai.goal.GoalType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 최근 판단들의 기록. 목표가 바뀔 때마다 한 건씩 쌓이고, 현재 계획과 최근 실패, 포기한 목표도 함께 남긴다.
 * Bukkit 에 의존하지 않는다.
 */
public final class DecisionLog {
    /**
     * @param action 실패한 행동. 계획 자체를 세우지 못했으면 "(계획 없음)".
     */
    public record Failure(long tick, GoalType goal, String action, String reason) {
    }

    /**
     * 계속 실패해서 한동안 쉬기로 한 목표.
     */
    public record GiveUp(long tick, GoalType goal, long restTicks, String lastReason) {
    }

    private static final int MAX_TRACES = 16;
    private static final int MAX_FAILURES = 12;
    private static final int MAX_GIVE_UPS = 8;

    private final Deque<DecisionTrace> traces = new ArrayDeque<>();
    private final Deque<Failure> failures = new ArrayDeque<>();
    private final Deque<GiveUp> giveUps = new ArrayDeque<>();
    private String plan = "";

    public void record(DecisionTrace trace) {
        traces.addLast(trace);
        if (traces.size() > MAX_TRACES) traces.removeFirst();
    }

    // 가장 최근 판단. 아직 한 번도 판단하지 않았으면 null.
    public @Nullable DecisionTrace current() {
        return traces.peekLast();
    }

    public List<DecisionTrace> history() {
        return List.copyOf(traces);
    }

    public void notePlan(String description) {
        plan = description;
    }

    public String getPlan() {
        return plan;
    }

    public void noteFailure(long tick, GoalType goal, String action, String reason) {
        failures.addLast(new Failure(tick, goal, action, reason));
        if (failures.size() > MAX_FAILURES) failures.removeFirst();
    }

    public @Nullable Failure lastFailure() {
        return failures.peekLast();
    }

    public List<Failure> getFailures() {
        return List.copyOf(failures);
    }

    public void noteGiveUp(long tick, GoalType goal, long restTicks, String lastReason) {
        giveUps.addLast(new GiveUp(tick, goal, restTicks, lastReason));
        if (giveUps.size() > MAX_GIVE_UPS) giveUps.removeFirst();
    }

    public List<GiveUp> getGiveUps() {
        return List.copyOf(giveUps);
    }

    public void clear() {
        traces.clear();
        failures.clear();
        giveUps.clear();
        plan = "";
    }
}
