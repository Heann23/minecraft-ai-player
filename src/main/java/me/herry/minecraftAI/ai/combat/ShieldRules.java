package me.herry.minecraftAI.ai.combat;

/**
 * 싸우는 도중에 언제 방패를 들지 정한다. 방패를 든 동안에는 칠 수 없으므로, 막아야 하는 순간에만 든다.
 * 방패는 든 지 5틱 뒤부터 막기 시작하므로 그만큼 앞서서 들어야 한다.
 */
public final class ShieldRules {
    // 크리퍼는 부풀기 시작한 지 30틱 뒤에 터진다. 이만큼 부풀었을 때 들면 터지기 전에 막을 준비가 끝난다.
    private static final int FUSE_TICKS = 6;
    // 스켈레톤은 활을 20틱 당긴 뒤에 쏜다. 당기기 시작하자마자 들지 않고 이만큼 당겼을 때 든다.
    private static final int DRAW_TICKS = 8;
    // 쏜 화살이 날아오는 동안에는 방패를 내리지 않는다 (15칸을 날아오는 데 10틱쯤 걸린다).
    private static final int ARROW_FLIGHT_TICKS = 12;

    private ShieldRules() {
    }

    /**
     * @param fuseTicks 크리퍼가 부풀기 시작한 뒤로 지난 틱 수. 부풀고 있지 않으면 0.
     */
    public static boolean blocksBlast(int fuseTicks) {
        return fuseTicks >= FUSE_TICKS;
    }

    /**
     * @param drawing           상대가 지금 활을 당기고 있는지
     * @param drawTicks         활을 당긴 지 지난 틱 수
     * @param ticksSinceDrawing 활을 당기는 것을 마지막으로 본 뒤로 지난 틱 수. 본 적이 없으면 아주 큰 값.
     */
    public static boolean blocksArrow(boolean drawing, int drawTicks, long ticksSinceDrawing) {
        if (drawing) return drawTicks >= DRAW_TICKS;
        return ticksSinceDrawing <= ARROW_FLIGHT_TICKS;
    }
}
