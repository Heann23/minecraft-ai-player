package me.herry.minecraftAI.ai.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShieldRulesTest {
    private static final long NEVER = Long.MAX_VALUE;

    // 크리퍼가 부풀기 시작하면 터지기 전에(30틱) 방패가 막기 시작하도록(5틱) 일찍 든다. 부풀지 않을 때는 들지 않고 친다.
    @Test
    void raisesTheShieldBeforeACreeperExplodes() {
        assertFalse(ShieldRules.blocksBlast(0));
        assertFalse(ShieldRules.blocksBlast(5));
        assertTrue(ShieldRules.blocksBlast(6));
        assertTrue(ShieldRules.blocksBlast(29));
    }

    // 회귀: 방패를 들고도 스켈레톤의 화살을 그대로 맞았다 (두 발에 6.8).
    // 활을 다 당기기(20틱) 전에 들어서, 쏘는 순간에는 방패가 이미 막고 있어야 한다.
    @Test
    void raisesTheShieldWhileASkeletonDrawsItsBow() {
        assertFalse(ShieldRules.blocksArrow(true, 0, 0));
        assertFalse(ShieldRules.blocksArrow(true, 7, 0));
        assertTrue(ShieldRules.blocksArrow(true, 8, 0));
        assertTrue(ShieldRules.blocksArrow(true, 20, 0));
    }

    // 쏜 화살이 날아오는 동안에는 방패를 내리지 않고, 그 뒤에는 내리고 다시 다가가서 친다.
    @Test
    void keepsTheShieldUpUntilTheArrowHasLanded() {
        assertTrue(ShieldRules.blocksArrow(false, 0, 1));
        assertTrue(ShieldRules.blocksArrow(false, 0, 12));
        assertFalse(ShieldRules.blocksArrow(false, 0, 13));
        // 활을 당기는 것을 본 적이 없으면 들지 않는다.
        assertFalse(ShieldRules.blocksArrow(false, 0, NEVER));
    }
}
