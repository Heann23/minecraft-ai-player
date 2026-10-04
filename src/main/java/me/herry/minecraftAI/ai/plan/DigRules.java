package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.function.Predicate;

/**
 * 땅을 팔 때 돌아갈 길을 잃지 않기 위한 규칙. 좌표만 보고 판단해서 서버 없이 테스트할 수 있다.
 */
public final class DigRules {
    private DigRules() {
    }

    /**
     * 돌처럼 흔한 블록을 얻으려고 캐도 되는 자리인지.
     * 발보다 낮은 블록을 캐면 구덩이가 생겨서 나오기 어렵고 그 아래의 동굴로 빠질 수 있다.
     * 파 내려온 계단의 발판을 캐면 계단이 끊겨서 올라갈 수 없다. 그래서 발 높이 이상의 벽만 캔다.
     *
     * @param isShaftStep 그 칸이 지나온 굴에서 발을 디디는 칸인지
     */
    public static boolean keepsWayOut(BlockPoint target, BlockPoint feet, Predicate<BlockPoint> isShaftStep) {
        if (target.y() < feet.y()) return false;
        return !isShaftStep.test(target.offset(0, 1, 0));
    }

    /**
     * 계단이나 굴을 내려고 그 블록을 파내면 지나온 굴이 끊기는지.
     * 발을 디디는 칸의 바로 아래 블록(발판)을 파내면 그 단을 디딜 수 없어서, 그 위로는 다시 올라갈 수 없다.
     */
    public static boolean cutsShaft(BlockPoint block, Predicate<BlockPoint> isShaftStep) {
        return isShaftStep.test(block.offset(0, 1, 0));
    }

    /**
     * 걸어서는 어디로도 갈 수 없을 때, 길을 파서 빠져나와야 하는지.
     *
     * @param miningDeep   깊은 땅속에서 광물을 캐는 중인지. 좁은 굴 안에 있는 것이 정상이다.
     * @param inShaft      파 놓은 굴 안에 있는지. 굴이 멀쩡하면 굴을 따라 걸어 나갈 수 있다.
     * @param shaftBlocked 방금 그 굴을 따라가려다 실패했는지 (굴이 끊기거나 막혔다)
     */
    public static boolean shouldDigOut(boolean miningDeep, boolean inShaft, boolean shaftBlocked) {
        if (miningDeep) return false;
        return !inShaft || shaftBlocked;
    }
}
