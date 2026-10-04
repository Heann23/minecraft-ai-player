package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;

/**
 * AI 가 수행하는 행동 하나. 여러 틱에 걸쳐 실행되며, 끝나면 SUCCESS 또는 FAILED 가 된다.
 */
public interface Action {
    String getName();

    ActionStatus getStatus();

    // FAILED 일 때의 실패 원인
    String getFailReason();

    // 매 틱 호출된다. 처음 호출될 때 READY 에서 RUNNING 으로 바뀐다.
    void update(AIPlayer ai);

    // 실행 도중 다른 목표 때문에 중단될 때 호출된다.
    void cancel(AIPlayer ai);
}
