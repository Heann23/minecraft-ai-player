package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.block.BlockFace;

import java.util.List;
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
     * 광물을 찾아 내려가다가 더 내려갈 수 없을 때(물이나 용암에 닿는 등), 그 높이에서 옆으로 굴을 파야 하는지.
     * 목표 높이 근처(slack 이내)면 그 높이에서 광물을 찾는다. 그보다 높아도 이미 땅속이면 옆으로 파서 막힌 자리를 비켜 간다.
     * 지상에서는 옆으로 파지 않는다. 자리를 옮겨서 다시 내려가면 되고, 지상의 땅을 한 줄로 파헤치게 된다.
     *
     * @param feetY       지금 발 높이
     * @param level       광물이 많이 나오는 높이
     * @param slack       목표 높이보다 이만큼 위까지는 목표 높이 근처로 친다
     * @param underground 머리 위로 땅이 두껍게 덮인 땅속인지
     */
    public static boolean shouldTunnelSideways(int feetY, int level, int slack, boolean underground) {
        return feetY <= level + slack || underground;
    }

    /**
     * 걸어갈 길이 없는 대상 쪽으로 길을 낼 때 시도할 방향의 순서. 곧장 가는 쪽(toward)이 먼저이고,
     * 그쪽을 팔 수 없을 때 비켜 갈 두 옆 방향 가운데서는 대상에 가까워지는 쪽이 먼저다.
     * 멀어지는 쪽부터 가면, 방금 판 굴로 되돌아갔다가 다시 오기를 되풀이한다 (화로 두 칸 앞에서 그렇게 멈춰 있었다).
     *
     * @param dx 대상까지 남은 x 거리 (대상 - 발)
     * @param dz 대상까지 남은 z 거리
     */
    public static List<BlockFace> sidesToward(BlockFace toward, int dx, int dz) {
        boolean alongX = toward.getModX() != 0;
        BlockFace negative = alongX ? BlockFace.NORTH : BlockFace.WEST;
        boolean negativeIsCloser = (alongX ? dz : dx) < 0;
        BlockFace first = negativeIsCloser ? negative : negative.getOppositeFace();
        return List.of(toward, first, first.getOppositeFace());
    }

    /**
     * 집 안이나 집 바로 옆에 서 있는지. 여기서 바로 파 내려가면 집의 바닥과 벽 밑을 허물게 되므로, 파기 전에 문밖으로 나간다.
     * 집보다 한참 아래에 있으면 해당하지 않는다. 깊은 굴이 집 밑을 지나갈 뿐이고, 거기서는 지상의 문밖으로 걸어갈 길도 없다.
     *
     * @param dx        집 가운데에서 발까지의 x 거리 (발 - 집)
     * @param dy        집 바닥 높이에서 발까지의 높이 차 (음수면 집보다 아래)
     * @param dz        집 가운데에서 발까지의 z 거리
     * @param clearance 집에서 이만큼 떨어져야 파도 되는 거리
     */
    public static boolean isAtBuilding(int dx, int dy, int dz, int clearance) {
        return Math.max(Math.abs(dx), Math.abs(dz)) <= clearance && dy >= -clearance;
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
