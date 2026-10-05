package me.herry.minecraftAI.ai.plan;

/**
 * "지상"과 "땅속"을 가르는 순수 규칙. 지형 판단(TerrainPlans)이 월드에서 읽은 높이를 넘겨주면 여기서 판단한다.
 *
 * 네더처럼 천장이 있는 차원에서는 가장 높은 블록이 천장의 기반암이다. 그것을 지표면으로 보면 어디에 있든
 * "깊은 땅속"이 되어, 지상에서 할 일을 하려고 천장까지 계단을 파게 된다. 그런 차원에는 올라갈 지상이 없다고 본다.
 */
public final class SurfaceRules {
    private SurfaceRules() {
    }

    /**
     * 머리 위로 땅이 두껍게 덮여 있는지.
     *
     * @param ceiling  천장이 있는 차원인지
     * @param surfaceY 그 자리의 지면에 섰을 때 발이 놓이는 높이
     * @param depth    이만큼 이상 덮여 있으면 깊은 땅속이다
     */
    public static boolean isDeepUnderground(boolean ceiling, int surfaceY, int feetY, int depth) {
        return !ceiling && surfaceY - feetY >= depth;
    }

    // 머리 위로 하늘이 열려 있는지. 천장이 있는 차원에서는 어디든 "더 올라갈 곳이 없는 바깥"으로 친다.
    public static boolean isUnderOpenSky(boolean ceiling, int surfaceY, int feetY) {
        return ceiling || surfaceY <= feetY;
    }

    // 그 자리의 지면이 발보다 minDiff 이상 높은지. 구덩이 안에 있는지 셀 때 쓴다.
    public static boolean isHigherGround(boolean ceiling, int surfaceY, int feetY, int minDiff) {
        return !ceiling && surfaceY - feetY >= minDiff;
    }
}
