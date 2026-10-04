package me.herry.minecraftAI.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIStateTest {
    @Test
    void runningAIKeepsRunningAfterARestart() {
        assertTrue(AIState.RUNNING.runsAfterRestore(false));
        assertFalse(AIState.STOPPED.runsAfterRestore(false));
    }

    // 회귀: 죽어서 리스폰을 기다리는 사이에 저장되면 "멈춤"으로 기록되어, 서버를 다시 켠 뒤에 움직이지 않았다.
    @Test
    void deadAIResumesAfterARestartIfItWasRunningWhenItDied() {
        assertTrue(AIState.DEAD.runsAfterRestore(true));
        // 죽은 뒤에 관리자가 멈추라고 했으면 되살아나도 멈춰 있는다.
        assertFalse(AIState.DEAD.runsAfterRestore(false));
    }
}
