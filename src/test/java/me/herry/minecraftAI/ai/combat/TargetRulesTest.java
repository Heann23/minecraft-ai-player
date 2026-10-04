package me.herry.minecraftAI.ai.combat;

import me.herry.minecraftAI.ai.combat.TargetRules.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetRulesTest {
    // 회귀: 가장 가까운 몬스터가 벽 너머의 다른 동굴에 있었는데 그것을 공격 대상으로 골랐다.
    // 닿지 않는 상대를 바라보며 서 있는 동안 옆에 온 좀비에게 맞았다.
    @Test
    void doesNotPickAMonsterBehindAWall() {
        Candidate behindWall = new Candidate(4.0, false, false, false);
        Candidate visible = new Candidate(9.0, true, false, false);
        assertEquals(1, TargetRules.pick(List.of(behindWall, visible)));
        assertEquals(-1, TargetRules.pick(List.of(behindWall)));
    }

    // 다가갈 길이 없었던 상대는 다시 고르지 않는다. 손이 닿을 만큼 가까이 왔으면 길이 생긴 것이므로 고른다.
    @Test
    void skipsAMonsterItCouldNotReachUntilItComesClose() {
        Candidate acrossTheRavine = new Candidate(8.0, true, false, true);
        Candidate other = new Candidate(12.0, true, false, false);
        assertEquals(1, TargetRules.pick(List.of(acrossTheRavine, other)));
        assertEquals(-1, TargetRules.pick(List.of(acrossTheRavine)));
        assertEquals(0, TargetRules.pick(List.of(new Candidate(3.0, true, false, true), other)));
        assertTrue(TargetRules.isOutOfReach(true, 8.0));
        assertFalse(TargetRules.isOutOfReach(true, 3.0));
        assertFalse(TargetRules.isOutOfReach(false, 8.0));
    }

    // 손이 닿는 상대가 가장 먼저다. 멀리서 활을 쏜 스켈레톤을 쫓느라 옆의 좀비를 내버려 두지 않는다.
    @Test
    void fightsWhatIsWithinReachFirst() {
        Candidate skeletonThatShotMe = new Candidate(12.0, true, true, false);
        Candidate zombieNextToMe = new Candidate(2.0, true, false, false);
        assertEquals(1, TargetRules.pick(List.of(skeletonThatShotMe, zombieNextToMe)));
    }

    // 손이 닿는 상대가 없으면 방금 나를 때린 상대를 먼저 쫓는다. 벽 너머에서 때렸더라도 상대로 친다.
    @Test
    void goesForTheOneThatHitIt() {
        Candidate nearer = new Candidate(6.0, true, false, false);
        Candidate attacker = new Candidate(10.0, false, true, false);
        assertEquals(1, TargetRules.pick(List.of(nearer, attacker)));
    }

    // 회귀: 쫓던 상대가 아닌 것에게 맞으면 공격을 "실패"로 끝내고 다시 계획했더니, 둘러싸였을 때 맞을 때마다
    // 실패가 쌓여서 한 대도 치지 못하고 죽었다. 실패로 끝내지 않고, 더 가까이에서 때린 쪽으로 상대를 바꾼다.
    @Test
    void turnsToACloserAttackerWhileStillChasing() {
        // 8칸 떨어진 좀비를 쫓는 중에 옆(2칸)의 좀비에게 맞았다.
        assertTrue(TargetRules.shouldSwitch(8.0, 2.0, 3.0));
        // 이미 손이 닿는 상대와 싸우는 중이면 하던 싸움을 끝낸다.
        assertFalse(TargetRules.shouldSwitch(2.5, 2.0, 3.0));
        // 더 멀리서 화살을 쏜 스켈레톤을 쫓느라 가까운 상대를 버리지 않는다.
        assertFalse(TargetRules.shouldSwitch(8.0, 12.0, 3.0));
    }

    @Test
    void otherwisePicksTheNearest() {
        assertEquals(1, TargetRules.pick(List.of(new Candidate(9.0, true, false, false), new Candidate(5.0, true, false, false))));
        assertEquals(-1, TargetRules.pick(List.of()));
    }
}
