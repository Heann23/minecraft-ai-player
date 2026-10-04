package me.herry.minecraftAI.ai.combat;

import me.herry.minecraftAI.ai.combat.CombatSystem.Decision;
import me.herry.minecraftAI.ai.combat.CombatSystem.Hostile;
import me.herry.minecraftAI.ai.perception.ThreatType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatSystemTest {
    private static final double HAND = 0.5;
    private static final double STONE_SWORD = 1.7;

    private final CombatSystem combat = new CombatSystem(10.0, 4.0);

    private static Hostile zombie(double distance) {
        return new Hostile(ThreatType.ZOMBIE, distance, false);
    }

    // 갑옷이 좋으면 같은 무기로도 더 많은 상대를 감당하고, 굶주렸으면 덜 감당한다.
    @Test
    void armorAndHungerChangeHowMuchItCanTakeOn() {
        assertEquals(1.0, CombatSystem.readiness(0, 20), 1e-9);
        assertEquals(1.6, CombatSystem.readiness(15, 20), 1e-9);
        assertEquals(1.8, CombatSystem.readiness(20, 20), 1e-9);
        // 방어력 수치는 20 까지만 친다.
        assertEquals(1.8, CombatSystem.readiness(30, 20), 1e-9);
        assertEquals(0.7, CombatSystem.readiness(0, 6), 1e-9);
        assertEquals(1.0, CombatSystem.readiness(0, 7), 1e-9);

        // 돌 검만으로는 좀비 여섯을 피하지만, 철 갑옷을 입으면 맞서 싸운다.
        List<Hostile> sixZombies = List.of(zombie(4), zombie(5), zombie(6), zombie(7), zombie(8), zombie(9));
        Decision unarmored = combat.decide(20, 20, STONE_SWORD * CombatSystem.readiness(0, 20), sixZombies);
        Decision armored = combat.decide(20, 20, STONE_SWORD * CombatSystem.readiness(15, 20), sixZombies);
        assertEquals(Decision.FLEE, unarmored);
        assertEquals(Decision.FIGHT, armored);
    }

    // 회귀: 크리퍼가 5칸 안에 오면 무조건 물러났다가 일하던 자리로 돌아오기를 되풀이했다.
    // 방패가 있으면 폭발을 막을 수 있으므로 물러나지 않고 상대한다.
    @Test
    void standsItsGroundAgainstACreeperWhenItCanBlockTheBlast() {
        List<Hostile> creeper = List.of(new Hostile(ThreatType.CREEPER, 3.0, true));
        assertEquals(Decision.FLEE, combat.decide(20, 20, STONE_SWORD, creeper, false));
        assertEquals(Decision.FIGHT, combat.decide(20, 20, STONE_SWORD, creeper, true));
        // 방패가 있어도 체력이 위태로우면 물러난다.
        assertEquals(Decision.FLEE, combat.decide(4, 20, STONE_SWORD, creeper, true));
    }

    // 방패가 없어도 돌 검이 있으면 치고 물러나기로 크리퍼를 상대한다. 맨손으로는 너무 오래 걸리므로 하지 않고,
    // 방금 물러날 자리가 없었으면(좁은 굴 등) 하지 않는다. 그럴 때는 예전처럼 달아난다.
    @Test
    void hitsAndRunsOnlyWithARealWeaponAndRoomToRetreat() {
        assertTrue(CombatSystem.canHitAndRun(STONE_SWORD, false));
        assertFalse(CombatSystem.canHitAndRun(HAND, false));
        assertFalse(CombatSystem.canHitAndRun(STONE_SWORD, true));

        CombatMemory memory = new CombatMemory();
        assertFalse(memory.retreatBlockedWithin(1000, 400));
        memory.onRetreatBlocked(1000);
        assertTrue(memory.retreatBlockedWithin(1400, 400));
        assertFalse(memory.retreatBlockedWithin(1401, 400));
    }

    @Test
    void noHostilesMeansNoCombat() {
        assertEquals(Decision.NONE, combat.decide(20, 20, HAND, List.of()));
    }

    @Test
    void ignoresDistantHostileThatIsNotTargeting() {
        assertEquals(Decision.NONE, combat.decide(20, 20, STONE_SWORD, List.of(zombie(18))));
    }

    @Test
    void waitsUntilTargetingHostileComesIntoRange() {
        // 멀리서 쫓아오는 몬스터를 향해 먼저 달려가지 않고, 교전 범위에 들어오면 대응한다.
        assertEquals(Decision.NONE, combat.decide(20, 20, STONE_SWORD, List.of(new Hostile(ThreatType.ZOMBIE, 18, true))));
        assertEquals(Decision.FIGHT, combat.decide(20, 20, STONE_SWORD, List.of(new Hostile(ThreatType.ZOMBIE, 9, true))));
    }

    @Test
    void fightsSingleZombieBareHandedAtFullHealth() {
        assertEquals(Decision.FIGHT, combat.decide(20, 20, HAND, List.of(zombie(5))));
    }

    @Test
    void fleesFromGroupWhenBareHandedButFightsWithSword() {
        List<Hostile> group = List.of(zombie(4), zombie(6), zombie(8));
        assertEquals(Decision.FLEE, combat.decide(20, 20, HAND, group));
        assertEquals(Decision.FIGHT, combat.decide(20, 20, STONE_SWORD, group));
    }

    @Test
    void fleesAtCriticalHealthEvenWithGoodWeapon() {
        assertEquals(Decision.FLEE, combat.decide(4, 20, STONE_SWORD, List.of(zombie(5))));
    }

    @Test
    void lowHealthReducesWillingnessToFight() {
        List<Hostile> pair = List.of(zombie(4), zombie(6));
        assertEquals(Decision.FIGHT, combat.decide(20, 20, STONE_SWORD, pair));
        assertEquals(Decision.FLEE, combat.decide(6, 20, STONE_SWORD, pair));
    }

    @Test
    void alwaysBacksAwayFromCloseCreeper() {
        Hostile creeper = new Hostile(ThreatType.CREEPER, 3, false);
        assertEquals(Decision.FLEE, combat.decide(20, 20, 2.5, List.of(creeper)));
    }

    @Test
    void nonMobThreatsAreIgnored() {
        Hostile lava = new Hostile(ThreatType.LAVA, 1, false);
        assertEquals(Decision.NONE, combat.decide(20, 20, HAND, List.of(lava)));
    }
}
