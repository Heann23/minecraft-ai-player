package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;

/**
 * 상태 전환과 제한 시간을 공통으로 처리한다. 개별 행동은 onStart / onTick 안에서 succeed() 나 fail() 을 호출하면 된다.
 */
public abstract class AbstractAction implements Action {
    private final String name;
    private final int timeoutTicks;
    private ActionStatus status = ActionStatus.READY;
    private String failReason = "";
    private int elapsed;

    protected AbstractAction(String name, int timeoutTicks) {
        this.name = name;
        this.timeoutTicks = timeoutTicks;
    }

    protected abstract void onStart(AIPlayer ai);

    protected abstract void onTick(AIPlayer ai);

    // 성공, 실패, 중단 어느 쪽으로든 행동이 끝날 때 한 번 호출된다. 이동 입력처럼 잡고 있던 것을 여기서 놓는다.
    protected void onEnd(AIPlayer ai) {
    }

    @Override
    public final void update(AIPlayer ai) {
        if (status == ActionStatus.READY) {
            status = ActionStatus.RUNNING;
            onStart(ai);
        }
        if (status == ActionStatus.RUNNING) {
            // 어떤 행동도 끝없이 실행되지 않도록 제한 시간을 둔다.
            if (++elapsed > timeoutTicks) fail("timeout");
            else onTick(ai);
        }
        if (status != ActionStatus.RUNNING) onEnd(ai);
    }

    @Override
    public final void cancel(AIPlayer ai) {
        if (status != ActionStatus.RUNNING) return;
        status = ActionStatus.FAILED;
        failReason = "cancelled";
        onEnd(ai);
    }

    protected final void succeed() {
        if (status == ActionStatus.RUNNING) status = ActionStatus.SUCCESS;
    }

    protected final void fail(String reason) {
        if (status != ActionStatus.RUNNING) return;
        status = ActionStatus.FAILED;
        failReason = reason;
    }

    protected final int getElapsed() {
        return elapsed;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final ActionStatus getStatus() {
        return status;
    }

    @Override
    public final String getFailReason() {
        return failReason;
    }
}
