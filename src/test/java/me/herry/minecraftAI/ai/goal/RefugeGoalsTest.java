package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.combat.CombatMemory;
import me.herry.minecraftAI.ai.combat.CombatSystem;
import me.herry.minecraftAI.ai.survival.SurvivalSystem;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 몬스터에게 몰렸을 때 숨고, 숨은 자리에서 회복한 뒤에 나오는 판단.
 */
class RefugeGoalsTest {
    private final GoalSystem goals = new GoalSystem();

    // 철을 찾던 중에 숨은 상황. 체력은 "낮음" 기준(8)보다는 높지만 다시 나가 싸우기에는 모자란다.
    private static Situation hidden(double health) {
        Situation situation = new Situation();
        situation.nextMilestone = Milestone.IRON_SWORD;
        situation.need = Situation.Need.IRON;
        situation.canMineIron = true;
        situation.sealedIn = true;
        situation.health = health;
        situation.maxHealth = 20;
        situation.healthState = health <= 8 ? SurvivalSystem.HealthState.LOW : SurvivalSystem.HealthState.OK;
        return situation;
    }

    private GoalType select(Situation situation) {
        return goals.select(situation, 0L).goal();
    }

    // 회귀: 체력이 조금 돌아오자마자 하던 일로 돌아가서 다시 몬스터 한가운데로 나갔다.
    // 숨은 자리에서는 체력이 넉넉해질 때까지(80%) 먹고 쉰다.
    @Test
    void staysHiddenUntilHealthIsBack() {
        Situation hurt = hidden(12);
        hurt.canRegenerate = true;
        assertEquals(GoalType.SURVIVE, select(hurt));

        Situation recovered = hidden(16);
        recovered.canRegenerate = true;
        assertNotEquals(GoalType.SURVIVE, select(recovered));
    }

    // 먹을 것이 있으면 허기가 모자라도 먹어서 회복할 수 있다.
    @Test
    void eatsInsideTheRefuge() {
        Situation hurt = hidden(12);
        hurt.canRegenerate = false;
        hurt.food = 14;
        hurt.hasFood = true;
        assertEquals(GoalType.SURVIVE, select(hurt));
    }

    // 회복할 방법이 없으면(허기가 모자라고 음식도 없음) 기다려도 소용없으니 나가서 먹을 것을 구한다.
    @Test
    void leavesWhenItCannotRecoverInside() {
        Situation hurt = hidden(12);
        hurt.canRegenerate = false;
        hurt.food = 14;
        hurt.hasFood = false;
        assertNotEquals(GoalType.SURVIVE, select(hurt));
    }

    // 땅속에서는 달아나지 않고 숨는다. 지상에서는 달아나 보고, 달아나지 못했을 때만 숨는다. 막을 블록이 없으면 숨을 수 없다.
    @Test
    void takesRefugeUndergroundOrAfterAFailedFlight() {
        assertTrue(CombatSystem.shouldTakeRefuge(true, false, 2));
        assertFalse(CombatSystem.shouldTakeRefuge(false, false, 64));
        assertTrue(CombatSystem.shouldTakeRefuge(false, true, 2));
        assertFalse(CombatSystem.shouldTakeRefuge(true, true, 1));
    }

    // 회귀: "몬스터가 있는 빈 곳으로는 굴을 뚫지 않는다"를 늘 적용했더니, 다이아몬드를 찾아 내려가던 굴이
    // 몬스터가 있는 동굴 옆에서 어느 쪽으로도 나아가지 못했다. 숨은 직후 2분 동안만 조심한다.
    @Test
    void isWaryOfOpeningTowardMonstersOnlyForAWhileAfterHiding() {
        CombatMemory memory = new CombatMemory();
        assertFalse(memory.isWaryAfterRefuge(5000));
        memory.onRefuge(5000);
        assertTrue(memory.isWaryAfterRefuge(5001));
        assertTrue(memory.isWaryAfterRefuge(7400));
        assertFalse(memory.isWaryAfterRefuge(7401));
        memory.reset();
        assertFalse(memory.isWaryAfterRefuge(5001));
    }

    @Test
    void remainsWaryWhileProtectedEvenAfterTheInitialTwoMinuteWindow() {
        CombatMemory memory = new CombatMemory();
        memory.onRefuge(5000);
        assertTrue(memory.updateSealedIn(true, 5001));
        assertFalse(memory.updateSealedIn(true, 8000));
        assertTrue(memory.isWaryAfterRefuge(8000));
        assertTrue(memory.updateSealedIn(false, 8001));
        assertTrue(memory.isWaryAfterRefuge(10400));
        assertFalse(memory.isWaryAfterRefuge(10401));
        memory.reset();
        assertFalse(memory.isWaryAfterRefuge(8000));
    }

    // 다가갈 길이 없었던 몬스터는 한동안(300틱) 적어 두었다가 다시 시도한다.
    @Test
    void remembersUnreachableMonstersForAWhile() {
        CombatMemory memory = new CombatMemory();
        UUID zombie = UUID.randomUUID();
        assertFalse(memory.isUnreachable(zombie, 1000));
        memory.markUnreachable(zombie, 1000);
        assertTrue(memory.isUnreachable(zombie, 1300));
        assertFalse(memory.isUnreachable(zombie, 1301));
        memory.reset();
        assertFalse(memory.isUnreachable(zombie, 1000));
    }
}
