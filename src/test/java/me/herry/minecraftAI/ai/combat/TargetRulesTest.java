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

    @Test
    void otherwisePicksTheNearest() {
        assertEquals(1, TargetRules.pick(List.of(new Candidate(9.0, true, false, false), new Candidate(5.0, true, false, false))));
        assertEquals(-1, TargetRules.pick(List.of()));
    }
}
