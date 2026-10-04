package me.herry.minecraftAI.ai.combat;

import java.util.List;

/**
 * 여러 몬스터 가운데 누구를 먼저 칠지 정한다. 입력이 단순한 값뿐이라 서버 없이도 테스트할 수 있다.
 */
public final class TargetRules {
    // 이 거리 안이면 다가갈 길이 없다고 적어 둔 상대라도 손이 닿는다.
    private static final double MELEE_RANGE = 3.5;

    /**
     * @param engaged     보이거나, 나를 노리고 있거나, 방금까지 보이던 상대인지. 벽 너머의 몬스터는 상대하지 않는다.
     * @param attackedMe  방금 나를 때린 상대인지
     * @param unreachable 다가갈 길이 없었던 상대인지
     */
    public record Candidate(double distance, boolean engaged, boolean attackedMe, boolean unreachable) {
    }

    private TargetRules() {
    }

    /**
     * 공격할 상대의 순번. 마땅한 상대가 없으면 -1.
     * 손이 닿는 상대가 가장 먼저이고, 그다음이 방금 나를 때린 상대, 그다음이 가까운 상대다.
     */
    public static int pick(List<Candidate> candidates) {
        int best = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!candidate.engaged() && !candidate.attackedMe()) continue;
            if (isOutOfReach(candidate.unreachable(), candidate.distance())) continue;
            if (best < 0 || isBetter(candidate, candidates.get(best))) best = i;
        }
        return best;
    }

    /**
     * 싸우는 도중에 다른 몬스터에게 맞았을 때 그쪽으로 상대를 바꿀지.
     * 지금 상대에게 이미 손이 닿으면 하던 싸움을 끝내고, 아직 쫓거나 기다리는 중이면 더 가까이에서 때린 쪽부터 상대한다.
     *
     * @param reach 손이 닿는 거리
     */
    public static boolean shouldSwitch(double targetDistance, double attackerDistance, double reach) {
        return targetDistance > reach && attackerDistance < targetDistance;
    }

    // 다가갈 길이 없고 손도 닿지 않는 상대인지. 저쪽에서도 이쪽으로 오지 못하므로, 활을 쏘지 않는 한 위협이 아니다.
    public static boolean isOutOfReach(boolean unreachable, double distance) {
        return unreachable && distance > MELEE_RANGE;
    }

    private static boolean isBetter(Candidate a, Candidate b) {
        boolean aClose = a.distance() <= MELEE_RANGE;
        boolean bClose = b.distance() <= MELEE_RANGE;
        if (aClose != bClose) return aClose;
        if (!aClose && a.attackedMe() != b.attackedMe()) return a.attackedMe();
        return a.distance() < b.distance();
    }
}
