package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.List;

/**
 * 발이 놓일 블록 좌표의 순서.
 *
 * @param partial 목표까지 닿지 못하고 가장 가까운 지점에서 끝난 경로인지
 */
public record Path(List<BlockPoint> points, boolean partial) {
    public int size() {
        return points.size();
    }

    public BlockPoint get(int index) {
        return points.get(index);
    }
}
