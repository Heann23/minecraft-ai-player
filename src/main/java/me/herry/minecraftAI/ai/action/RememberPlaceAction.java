package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;

/**
 * 어떤 곳을 기억에 적어 둔다. 계획의 앞 단계(이동 등)가 실제로 끝났을 때만 실행되므로,
 * "가 보기로 한 곳"이 아니라 "정말 가 본 곳"만 기억하게 된다. 도중에 다른 일로 끊기면 적지 않는다.
 */
public final class RememberPlaceAction extends AbstractAction {
    private final MemoryType type;
    private final BlockPoint place;
    private final long ttlTicks;

    public RememberPlaceAction(MemoryType type, BlockPoint place, long ttlTicks) {
        super("RememberPlace", 5);
        this.type = type;
        this.place = place;
        this.ttlTicks = ttlTicks;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ai.getMemory().remember(type, ai.getWorldId(), place, ai.getTicks(), ttlTicks);
        succeed();
    }

    @Override
    protected void onTick(AIPlayer ai) {
    }
}
