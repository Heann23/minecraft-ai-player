package me.herry.minecraftAI.ai.brain;

import me.herry.minecraftAI.ai.comm.Intent;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Situation;
import org.jetbrains.annotations.Nullable;

/**
 * 사람이 대화로 부탁한 일. 관리자의 목표 고정(/ai goal)과 달리, 위험 탈출 같은 긴급 목표가 생기면 그쪽을 먼저 하고
 * 정해진 시간이 지나거나 일을 마치면 스스로 판단하는 상태로 돌아간다.
 * 부탁은 "무엇을"까지만 정하고, 그것을 위해 지금 할 단기 목표는 그때의 상황을 보고 고른다. Bukkit 에 의존하지 않는다.
 *
 * @param subject     구해 올 자원. GO_HOME 이면 NONE.
 * @param requestedBy 부탁한 사람의 이름
 */
public record Directive(Kind kind, Intent.Subject subject, String requestedBy, long expiresAt) {
    public enum Kind { GO_HOME, GATHER }

    // 자원을 구해 오라는 부탁은 이 시간 동안 따른다. 그 뒤에는 하던 진행으로 돌아간다.
    public static final long GATHER_TICKS = 3600L;
    public static final long GO_HOME_TICKS = 2400L;
    private static final double HOME_ARRIVED_RANGE = 4.0;

    public static Directive goHome(String requestedBy, long now) {
        return new Directive(Kind.GO_HOME, Intent.Subject.NONE, requestedBy, now + GO_HOME_TICKS);
    }

    public static Directive gather(Intent.Subject subject, String requestedBy, long now) {
        return new Directive(Kind.GATHER, subject, requestedBy, now + GATHER_TICKS);
    }

    /**
     * 이 부탁을 지금 들어줄 수 없는 이유. 들어줄 수 있으면 null.
     */
    public @Nullable String rejection(Situation s) {
        if (kind == Kind.GO_HOME) return s.homeKnown ? null : "아직 돌아갈 집이 없어요.";
        return switch (subject) {
            case STONE, COAL -> s.hasPickaxe ? null : "곡괭이가 없어서 아직 캘 수 없어요.";
            case IRON -> s.canMineIron ? null : "돌 곡괭이 이상이 있어야 철을 캘 수 있어요.";
            case DIAMOND -> s.canMineDiamond ? null : "철 곡괭이 이상이 있어야 다이아몬드를 캘 수 있어요.";
            case WOOD, FOOD -> null;
            case NONE -> "무엇을 구해 올지 모르겠어요.";
        };
    }

    public boolean isExpired(long now) {
        return now >= expiresAt;
    }

    // 부탁한 일을 마쳤는지. 집에 도착했으면 귀환은 끝난 것이다. 자원 모으기는 시간이 다 될 때까지 계속한다.
    public boolean isDone(Situation s) {
        return kind == Kind.GO_HOME && s.homeKnown && (s.insideHome || s.homeDistance <= HOME_ARRIVED_RANGE);
    }

    /**
     * 이 부탁을 위해 지금 할 단기 목표. 이미 아는 것이 있으면 캐러 가고, 없으면 찾으러 간다.
     */
    public GoalType goalFor(Situation s) {
        if (kind == Kind.GO_HOME) return GoalType.RETURN_HOME;
        return switch (subject) {
            case WOOD -> s.knowsTree || s.treeUnfinished ? GoalType.COLLECT_WOOD : GoalType.FIND_WOOD;
            case STONE -> GoalType.MINE_STONE;
            case COAL -> s.knowsCoal ? GoalType.MINE_COAL : GoalType.FIND_IRON;
            case IRON -> s.knowsIron ? GoalType.MINE_IRON : GoalType.FIND_IRON;
            case DIAMOND -> s.knowsDiamond ? GoalType.MINE_DIAMOND : GoalType.FIND_DIAMOND;
            case FOOD -> s.hasFood && s.shouldEat ? GoalType.FIND_FOOD : GoalType.STOCK_FOOD;
            case NONE -> GoalType.EXPLORE;
        };
    }

    public String describe() {
        if (kind == Kind.GO_HOME) return requestedBy + " 님이 집으로 돌아오라고 해서요";
        return requestedBy + " 님이 " + subjectLabel(subject) + " 구해 오라고 해서요";
    }

    public static String subjectLabel(Intent.Subject subject) {
        return switch (subject) {
            case WOOD -> "나무를";
            case STONE -> "돌을";
            case COAL -> "석탄을";
            case IRON -> "철을";
            case DIAMOND -> "다이아몬드를";
            case FOOD -> "먹을 것을";
            case NONE -> "무언가를";
        };
    }
}
