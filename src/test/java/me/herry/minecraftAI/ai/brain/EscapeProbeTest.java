package me.herry.minecraftAI.ai.brain;

import me.herry.minecraftAI.ai.goal.GoalType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 깊은 구덩이에서 길을 파서 올라오는 동안의 실패를 어떻게 세는지.
 * (시드 355843249: 13칸 깊이의 구덩이를 한 단씩 올라오는 동안 "아직 갇혀 있음"이 실패로 세어져서 목표가 다섯 번 쉬었다.)
 */
class EscapeProbeTest {
    // 한 단 파고 가 보니 아직 갇혀 있는 것은 실패가 아니다. 구덩이를 다 올라올 때까지 목표가 쉬지 않는다.
    @Test
    void stillTrappedRightAfterAnEscapeStepIsNotAFailure() {
        RecoveryTracker recovery = new RecoveryTracker();
        for (int step = 0; step < 13; step++) {
            recovery.onEscapeStep();
            assertFalse(recovery.countsAsFailure(true), "step " + step);
        }
        assertEquals(0, recovery.failureStreak(GoalType.COLLECT_WOOD));
    }

    // 길을 판 직후가 아니면 갇혀서 실패한 것도 평소대로 센다 (처음 갇혔을 때, 길을 팔 방법이 없을 때).
    @Test
    void trappedWithoutAnEscapeStepCounts() {
        RecoveryTracker recovery = new RecoveryTracker();
        assertTrue(recovery.countsAsFailure(true));
        recovery.onEscapeStep();
        assertFalse(recovery.countsAsFailure(true));
        // 봐주는 것은 길을 판 바로 다음의 한 번뿐이다.
        assertTrue(recovery.countsAsFailure(true));
    }

    // 길을 판 뒤에 다른 까닭으로 실패했으면 빠져나온 것이다. 그 실패는 목표의 실패다.
    @Test
    void otherFailuresAfterAnEscapeStepCount() {
        RecoveryTracker recovery = new RecoveryTracker();
        recovery.onEscapeStep();
        assertTrue(recovery.countsAsFailure(false));
        // 그 뒤의 갇힘은 새로 갇힌 것이라서 센다.
        assertTrue(recovery.countsAsFailure(true));
    }

    // 파도 나아지지 않는 자리에서 끝없이 되풀이하지 않는다. 한계를 넘으면 다시 실패로 세서 목표를 쉬게 한다.
    @Test
    void probesAreLimited() {
        RecoveryTracker recovery = new RecoveryTracker();
        for (int i = 0; i < RecoveryTracker.MAX_ESCAPE_PROBES; i++) {
            recovery.onEscapeStep();
            assertFalse(recovery.countsAsFailure(true));
        }
        recovery.onEscapeStep();
        assertTrue(recovery.countsAsFailure(true));

        // 목표를 한 번 이루면 다시 처음부터 봐준다.
        recovery.onPlanSucceeded(GoalType.COLLECT_WOOD);
        recovery.onEscapeStep();
        assertFalse(recovery.countsAsFailure(true));
    }
}
