package me.herry.minecraftAI.ai.perception;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityTest {
    private final Set<BlockPoint> solid = new HashSet<>();
    private final Visibility.Opacity opacity = (x, y, z) -> solid.contains(new BlockPoint(x, y, z));

    private boolean sees(double ex, double ey, double ez, int x, int y, int z) {
        return Visibility.canSeeBlock(opacity, ex, ey, ez, x, y, z);
    }

    @Test
    void seesBlockInOpenAir() {
        solid.add(new BlockPoint(5, 0, 0));
        assertTrue(sees(0.5, 1.6, 0.5, 5, 0, 0));
    }

    @Test
    void wallHidesBlockBehindIt() {
        // x=3 위치에 높이 3칸의 벽을 세우고 그 뒤의 광석을 본다.
        for (int y = -2; y <= 4; y++) {
            for (int z = -3; z <= 3; z++) solid.add(new BlockPoint(3, y, z));
        }
        solid.add(new BlockPoint(6, 1, 0));
        assertFalse(sees(0.5, 1.6, 0.5, 6, 1, 0));
    }

    @Test
    void buriedBlockIsHiddenEvenIfAdjacentToAir() {
        // 광석은 다른 동굴의 공기와 맞닿아 있지만, AI 쪽에서는 돌에 막혀 있다.
        for (int x = 2; x <= 6; x++) {
            for (int y = -1; y <= 3; y++) {
                for (int z = -2; z <= 2; z++) solid.add(new BlockPoint(x, y, z));
            }
        }
        solid.remove(new BlockPoint(5, 1, 1));
        assertFalse(sees(0.5, 1.6, 0.5, 5, 1, 0));
    }

    @Test
    void exposedFaceIsVisibleWhenCenterRayIsBlocked() {
        // 앞의 블록이 중심을 향한 시선을 가리지만, 광석 윗면은 보인다.
        solid.add(new BlockPoint(4, 0, 0));
        solid.add(new BlockPoint(3, 0, 0));
        assertTrue(sees(0.5, 2.6, 0.5, 4, 0, 0));
    }

    @Test
    void pointBehindWallIsHidden() {
        for (int y = 0; y <= 3; y++) solid.add(new BlockPoint(2, y, 0));
        assertFalse(Visibility.canSeePoint(opacity, 0.5, 1.6, 0.5, 4.5, 1.0, 0.5));
        assertTrue(Visibility.canSeePoint(opacity, 0.5, 1.6, 0.5, 0.5, 1.0, 4.5));
    }
}
