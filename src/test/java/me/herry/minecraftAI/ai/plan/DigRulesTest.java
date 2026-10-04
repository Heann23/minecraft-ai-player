package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigRulesTest {
    // 동쪽으로 한 단씩 내려온 계단의 발 위치. 지금 맨 아래 단에 서 있다.
    private static final BlockPoint FEET = new BlockPoint(42, 68, 169);
    private static final Set<BlockPoint> STEPS = Set.of(new BlockPoint(40, 70, 169), new BlockPoint(41, 69, 169), FEET);

    private static boolean keepsWayOut(int x, int y, int z) {
        return DigRules.keepsWayOut(new BlockPoint(x, y, z), FEET, STEPS::contains);
    }

    // 회귀: 방금 내려온 계단의 발판을 캐서 계단이 끊겼고, 굴 안에 갇힌 채로 끝났다.
    @Test
    void doesNotMineTheStepsOfItsOwnStair() {
        assertFalse(keepsWayOut(41, 68, 169));
        assertFalse(keepsWayOut(40, 69, 169));
    }

    // 회귀: 발밑과 옆 바닥을 캐서 구덩이가 생겼고, 그 구멍으로 물이 찬 동굴에 빠졌다.
    @Test
    void doesNotMineBelowItsFeet() {
        assertFalse(keepsWayOut(42, 67, 169));
        assertFalse(keepsWayOut(43, 67, 169));
        assertFalse(keepsWayOut(42, 66, 170));
    }

    @Test
    void minesTheWallsAroundIt() {
        // 발 높이와 머리 높이의 앞벽과 옆벽
        assertTrue(keepsWayOut(43, 68, 169));
        assertTrue(keepsWayOut(42, 68, 168));
        assertTrue(keepsWayOut(42, 69, 170));
        assertTrue(keepsWayOut(43, 70, 169));
    }

    // 회귀: 서쪽으로 파 내려온 계단의 맨 아래에서 동쪽으로 수평 굴을 팠다. 굴이 계단 바로 밑을 지나가면서 발판을
    // 밑에서 파냈고, 굴을 따라 올라가려다 "갇힘"으로 실패했다.
    @Test
    void doesNotTunnelUnderItsOwnStair() {
        Set<BlockPoint> stair = Set.of(new BlockPoint(-9, 24, 206), new BlockPoint(-8, 25, 206), new BlockPoint(-7, 26, 206));
        // 굴의 첫 칸: 발 높이의 블록이 바로 윗단의 발판이다.
        assertTrue(DigRules.cutsShaft(new BlockPoint(-8, 24, 206), stair::contains));
        // 굴의 둘째 칸: 머리 높이의 블록이 그다음 단의 발판이다.
        assertTrue(DigRules.cutsShaft(new BlockPoint(-7, 25, 206), stair::contains));
        // 계단이 온 방향의 반대쪽(서쪽)이나 옆으로 내는 굴은 발판을 건드리지 않는다.
        assertFalse(DigRules.cutsShaft(new BlockPoint(-10, 24, 206), stair::contains));
        assertFalse(DigRules.cutsShaft(new BlockPoint(-10, 25, 206), stair::contains));
        assertFalse(DigRules.cutsShaft(new BlockPoint(-9, 24, 205), stair::contains));
    }

    // 회귀: 계단 바닥에서 옆으로 몇 칸 걸어간 뒤 그 자리에서 새 계단을 파 내려갔다. 새 계단이 방금 걸어온 칸의 바닥을
    // 파내서 두 계단 사이의 길이 끊겼고, 올라가려다 "갇힘"으로 실패했다. 걸어온 칸은 굴로 기록되지 않아서 보호받지 못했다.
    @Test
    void doesNotDigOutTheFloorItJustWalkedOn() {
        // 옛 계단의 바닥 (-28,62,241) 에서 (-29,62,241) → (-29,62,242) → (-29,62,243) 으로 걸어왔다.
        Set<BlockPoint> walked = Set.of(new BlockPoint(-28, 62, 241), new BlockPoint(-29, 62, 241),
                new BlockPoint(-29, 62, 242), new BlockPoint(-29, 62, 243));
        // 북쪽으로 내려가는 새 계단의 첫 단 (-29,61,242) 은 걸어온 칸 (-29,62,242) 의 바닥이다.
        assertTrue(DigRules.cutsShaft(new BlockPoint(-29, 61, 242), walked::contains));
        // 서쪽으로 내려가는 계단은 걸어온 길을 건드리지 않는다.
        assertFalse(DigRules.cutsShaft(new BlockPoint(-30, 62, 243), walked::contains));
        assertFalse(DigRules.cutsShaft(new BlockPoint(-30, 61, 243), walked::contains));
    }

    @Test
    void doesNotDigOutOfAShaftItCanWalk() {
        // 굴 안은 좁아서 "갇혔다"는 판정이 나오기 쉽지만, 굴을 따라 걸어 나갈 수 있다.
        assertFalse(DigRules.shouldDigOut(false, true, false));
    }

    // 회귀: 끊긴 굴 안에 있어서 "굴 안이니 갇힌 것이 아니다"로 판단하고 제자리에서 쉬기만 했다.
    @Test
    void digsOutWhenTheShaftIsBlocked() {
        assertTrue(DigRules.shouldDigOut(false, true, true));
    }

    @Test
    void digsOutOfAPitThatIsNotAShaft() {
        assertTrue(DigRules.shouldDigOut(false, false, false));
    }

    @Test
    void staysDownWhileMiningDeepUnderground() {
        assertFalse(DigRules.shouldDigOut(true, false, false));
        assertFalse(DigRules.shouldDigOut(true, true, true));
    }
}
