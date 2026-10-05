package me.herry.minecraftAI.ai.primitive;

import me.herry.minecraftAI.ai.action.Action;

/**
 * 종류와 대상이 정해진 가장 작은 행동. Action 이기도 하므로 기존 실행 루프(Plan, AIBrain)에서 그대로 실행된다.
 * 상태(READY, RUNNING, SUCCESS, FAILED), 실패 원인, 제한 시간, 중단은 Action 의 것을 그대로 따른다.
 */
public interface PrimitiveAction extends Action {
    PrimitiveType getPrimitiveType();

    // 무엇에 대해 하는 행동인지. 기록으로 남기거나 나중에 학습 정책의 입력으로 쓴다.
    default PrimitiveTarget getTarget() {
        return PrimitiveTarget.NONE;
    }
}
