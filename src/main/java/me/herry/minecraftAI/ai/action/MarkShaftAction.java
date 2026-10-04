package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.util.BlockPoint;

/**
 * 굴을 한 칸 파고 들어선 자리를 동료와 함께 쓰는 굴 목록에 기록한다. 바로 끝나는 행동이다.
 * 실제로 그 칸까지 들어간 뒤에만 기록되도록 이동 행동 뒤에 둔다.
 */
public final class MarkShaftAction extends AbstractAction {
    private final BlockPoint point;

    public MarkShaftAction(BlockPoint point) {
        super("MarkShaft", 1);
        this.point = point;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ai.getTeam().getShafts().record(ai.getName(), ai.getWorldId(), point);
        succeed();
    }

    @Override
    protected void onTick(AIPlayer ai) {
    }
}
