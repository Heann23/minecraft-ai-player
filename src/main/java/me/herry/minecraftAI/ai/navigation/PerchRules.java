package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

/**
 * 나무 위처럼 걸어서 내려올 수 없는 높은 곳에서, 발밑 블록을 캐고 떨어져 내려와도 되는지 판단한다.
 * 좌표와 지형 분류만 보는 순수 규칙이라 서버 없이 테스트한다.
 */
public final class PerchRules {
    // 이 높이까지는 떨어져도 다치지 않는다.
    public static final int SAFE_FALL = 3;
    // 내려오려고 감수하는 피해의 한도와, 떨어진 뒤에 남아 있어야 하는 체력
    private static final int MAX_DAMAGE = 4;
    private static final double MIN_HEALTH_AFTER = 10.0;
    // 이보다 깊으면 어차피 뛰어내릴 수 없으므로 더 내려다보지 않는다.
    private static final int MAX_SCAN = SAFE_FALL + MAX_DAMAGE;

    private PerchRules() {
    }

    /**
     * @param drop 발밑 블록을 캤을 때 떨어지는 높이
     * @param soft 물 위로 떨어지는지 (다치지 않는다)
     */
    public record Landing(int drop, boolean soft) {
        public int damage() {
            return soft ? 0 : Math.max(0, drop - SAFE_FALL);
        }
    }

    /**
     * 발밑 블록 하나를 캤을 때 어디에 떨어지는지. 용암 같은 위험한 칸으로 떨어지거나,
     * 감수할 수 있는 높이 안에 바닥이 없거나, 읽을 수 없는 곳이면 null.
     */
    public static @Nullable Landing landingBelow(TerrainView terrain, BlockPoint feet) {
        for (int drop = 1; drop <= MAX_SCAN; drop++) {
            // 발밑 블록(feet.y - 1)이 없어진 뒤에 발이 놓일 칸의 바로 아래를 본다.
            BlockClass below = terrain.classify(feet.x(), feet.y() - drop - 1, feet.z());
            if (below == BlockClass.SOLID) return new Landing(drop, false);
            if (below == BlockClass.WATER) return new Landing(drop + 1, true);
            if (below != BlockClass.OPEN) return null;
        }
        return null;
    }

    // 다치지 않거나, 조금 다치더라도 체력이 넉넉히 남는 높이인지
    public static boolean isAcceptable(Landing landing, double health) {
        int damage = landing.damage();
        return damage <= MAX_DAMAGE && (damage == 0 || health - damage >= MIN_HEALTH_AFTER);
    }
}
