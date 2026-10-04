package me.herry.minecraftAI.ai.memory;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.UUID;

/**
 * 기억 한 건. 좌표가 다른 월드와 섞이지 않도록 월드 UUID 를 함께 저장한다.
 *
 * @param expiresTick 만료 시각 (AI 틱 기준). 음수면 만료되지 않는다.
 */
public record MemoryEntry(MemoryType type, UUID world, BlockPoint pos, long createdTick, long expiresTick) {
    public static final long PERMANENT = -1L;

    public boolean isExpired(long now) {
        return expiresTick >= 0 && now >= expiresTick;
    }
}
