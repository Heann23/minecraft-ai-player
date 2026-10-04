package me.herry.minecraftAI.ai.brain;

import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.goal.Stage;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 판단 한 번의 기록: 무엇을 골랐고, 다른 후보는 무엇이었고, 왜 골랐는지.
 * 디버그 명령과 대화("왜 그거 하고 있어?") 양쪽에서 같은 기록을 읽는다.
 *
 * @param origin    목표가 어디서 왔는지 (스스로 고름, 사람이 부탁함, 관리자가 고정함)
 * @param stage     그때의 장기 목표
 * @param milestone 그때의 중기 목표. 전부 이뤘으면 null.
 * @param reason    이 목표를 고른 이유 (사람이 읽는 문장)
 */
public record DecisionTrace(long tick, GoalType goal, double score, Origin origin, Stage stage, @Nullable Milestone milestone,
                            List<Candidate> candidates, String reason, @Nullable GoalType previousGoal) {
    public enum Origin { AUTONOMOUS, REQUESTED, FORCED }

    /**
     * @param resting 계속 실패해서 잠시 쉬는 중이라 고르지 않은 목표인지
     */
    public record Candidate(GoalType goal, double score, boolean resting) {
    }

    public boolean isEmergency(double emergencyScore) {
        return origin == Origin.AUTONOMOUS && score >= emergencyScore;
    }
}
