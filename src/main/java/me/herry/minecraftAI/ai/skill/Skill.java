package me.herry.minecraftAI.ai.skill;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.goal.model.Goal;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 목표 하나를 이루는 방법. 목표(무엇을)와 행동(손발을 움직이는 일) 사이에 있고, 같은 목표를 이루는 방법이 여럿일 수 있다.
 * SkillPlanner 가 그 목표를 맡을 수 있는 스킬 중에서 지금 쓸 수 있고 가장 싼 것을 골라 계획을 세우게 한다.
 *
 * supports, canExecute, estimateCost 는 값만 보고 답하므로 서버 없이 시험할 수 있다. buildPlan 만 월드를 읽는다.
 * 성공과 실패는 스킬이 따로 판정하지 않는다. 세운 행동들이 끝까지 성공했는지(Plan)와 목표의 완료 조건(Goal.isAchieved)으로 본다.
 */
public interface Skill {
    // SkillRegistry 안에서 겹치지 않는 이름. 기록과 로그에 남는다.
    String name();

    // 이 스킬로 수행할 수 있는 종류의 목표인지. 상황과 상관없는 판단이다.
    boolean supports(Goal goal);

    /**
     * 지금 상황에서 쓸 수 있는지 (선행 조건: 도구가 있는지, 캘 곳을 아는지 등).
     * 상황을 모르면(null) 조건을 따지지 않아도 되는 스킬만 true 를 돌려준다.
     */
    boolean canExecute(Goal goal, @Nullable Situation situation);

    // 드는 수고의 어림값. 작을수록 먼저 고른다. 단위는 없고 스킬끼리 견주는 데만 쓴다.
    double estimateCost(Goal goal, @Nullable Situation situation);

    // 지금 계획을 세울 수 없으면 빈 목록을 돌려준다. 그러면 다음으로 싼 스킬에게 넘어간다.
    List<Action> buildPlan(AIPlayer ai, Goal goal, @Nullable Situation situation);
}
