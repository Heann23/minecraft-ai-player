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

    // 실제 철기 회귀: 체력6·허기6·돌 도끼로 좀비 한 마리에게 먼저 다가가다 음식 탐색을 취소하고 죽었다.
    @Test
    void lowHealthPreventsStartingAMarginalFightAndStandingGround() {
        CombatSystem configured = new CombatSystem(16, 4, 8);
        double hungryAxe = STONE_SWORD * CombatSystem.readiness(0, 6);
        List<Hostile> single = List.of(zombie(15));
        assertTrue(CombatSystem.strength(6, 20, hungryAxe) > ThreatType.ZOMBIE.danger());
        Decision assessment = configured.decide(6, 20, hungryAxe, single);
        assertEquals(Decision.FLEE, assessment);
        assertFalse(CombatSystem.canStandGround(assessment, false, false, single));
        assertEquals(Decision.FLEE, configured.decide(8, 20, STONE_SWORD, single));
        assertEquals(Decision.FIGHT, configured.decide(9, 20, STONE_SWORD, single));
        assertEquals(Decision.NONE, configured.decide(6, 20, hungryAxe, List.of(zombie(21))));
    }

    // 회귀: 도주가 실패하자 체력 8로 여섯 마리에게 맞서면서, 막 시작한 피신굴을 취소했다.
    @Test
    void doesNotStandGroundWhenTheCurrentFightIsTooDangerous() {
        List<Hostile> group = List.of(new Hostile(ThreatType.SKELETON, 3, true), zombie(4), zombie(5),
                zombie(6), new Hostile(ThreatType.SPIDER, 7, true), new Hostile(ThreatType.CREEPER, 8, false));
        Decision assessment = combat.decide(8, 20, 3.36, group, true);
        assertEquals(Decision.FLEE, assessment);
        assertFalse(CombatSystem.canStandGround(assessment, false, false, group));

        List<Hostile> single = List.of(zombie(2));
        Decision critical = combat.decide(4, 20, 3.36, single, true);
        assertFalse(CombatSystem.canStandGround(critical, false, false, single));
    }

    // 보이는 상대는 감당 가능해도, 동굴 주변의 무리와 닿지 않는 활 공격 때문에 물러나는 판단은 보존한다.
    @Test
    void doesNotOverrideTheOtherReasonsToRetreat() {
        List<Hostile> visible = List.of(zombie(3));
        Decision assessment = combat.decide(20, 20, STONE_SWORD, visible);
        List<Hostile> around = List.of(zombie(3), zombie(4), zombie(5), zombie(6), zombie(7), zombie(8));
        boolean outnumbered = combat.isOutnumbered(20, 20, STONE_SWORD, around);
        assertEquals(Decision.FIGHT, assessment);
        assertTrue(outnumbered);
        assertFalse(CombatSystem.canStandGround(assessment, outnumbered, false, visible));
        assertFalse(CombatSystem.canStandGround(assessment, false, true, visible));
    }

    // 회귀: 크리퍼가 3칸 앞에 있어도 거미가 2칸 앞에 오면, 가장 가까운 적만 보고 다시 싸웠다.
    @Test
    void doesNotIgnoreACloseCreeperBehindANearerSpider() {
        List<Hostile> mixed = List.of(new Hostile(ThreatType.SPIDER, 2, true), new Hostile(ThreatType.CREEPER, 3, true));
        Decision unprotected = combat.decide(20, 20, 3.36, mixed, false);
        assertFalse(CombatSystem.canStandGround(unprotected, false, false, mixed));
        Decision protectedFight = combat.decide(20, 20, 3.36, mixed, true);
        assertEquals(Decision.FIGHT, protectedFight);
        assertFalse(CombatSystem.canStandGround(protectedFight, false, false, mixed));
    }

    // 감당 가능한 좀비에게서 달아나지 못하면, 기존 궁지 대응으로 맞설 수 있다.
    @Test
    void keepsStandingGroundAgainstAManageableNonExplosiveOpponent() {
        List<Hostile> single = List.of(zombie(2));
        Decision assessment = combat.decide(20, 20, STONE_SWORD, single);
        assertTrue(CombatSystem.canStandGround(assessment, false, false, single));
        assertFalse(CombatSystem.canStandGround(Decision.NONE, false, false, List.of()));
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

    // 회귀: 교전 범위의 경계(15~16칸)에 걸친 좀비 때문에 "달아나기"와 "집으로 가기"가 1초마다 뒤바뀌었다.
    // 이미 상대하던 중이면 범위보다 4칸 더 멀어질 때까지 계속 상대한다. 처음 상대하기 시작하는 거리는 그대로다.
    @Test
    void keepsEngagingUntilTheHostileIsWellOutOfRange() {
        List<Hostile> justOutside = List.of(zombie(11));
        assertEquals(Decision.NONE, combat.decide(20, 20, STONE_SWORD, justOutside, false, false));
        assertEquals(Decision.FIGHT, combat.decide(20, 20, STONE_SWORD, justOutside, false, true));
        assertEquals(Decision.FIGHT, combat.decide(20, 20, STONE_SWORD, List.of(zombie(14)), false, true));
        assertEquals(Decision.NONE, combat.decide(20, 20, STONE_SWORD, List.of(zombie(14.5)), false, true));

        CombatMemory memory = new CombatMemory();
        assertFalse(memory.wasEngaged());
        memory.updateLastDecision(Decision.FLEE);
        assertTrue(memory.wasEngaged());
        memory.updateLastDecision(Decision.NONE);
        assertFalse(memory.wasEngaged());
    }

    // 회귀: 다이아몬드 깊이의 굴이 몬스터가 가득한 동굴로 뚫렸다. 보이는 스켈레톤 하나를 잡으러 굴 밖으로 나섰다가
    // 몇 초 만에 거미, 크리퍼, 좀비에게 둘러싸여 죽었다 (철 검과 갑옷, 보이는 것은 1마리, 주변에는 14마리).
    @Test
    void countsTheMonstersItCannotSeeBeforeGoingOutToFightUnderground() {
        double ironGear = 3.36;
        List<Hostile> den = List.of(new Hostile(ThreatType.SKELETON, 9, true), zombie(6), zombie(8), zombie(9),
                new Hostile(ThreatType.SPIDER, 7, false), new Hostile(ThreatType.CREEPER, 8, false),
                new Hostile(ThreatType.CREEPER, 9, false), new Hostile(ThreatType.SKELETON, 10, false));
        // 보이는 스켈레톤 하나만 보면 싸울 만하지만, 주변을 다 합치면 감당할 수 없다.
        assertEquals(Decision.FIGHT, combat.decide(20, 20, ironGear, List.of(den.get(0))));
        assertTrue(combat.isOutnumbered(20, 20, ironGear, den));

        // 주변에 한두 마리뿐이면 평소대로 싸운다.
        assertFalse(combat.isOutnumbered(20, 20, ironGear, List.of(den.get(0), zombie(8))));
        // 교전 범위 밖에 있는 몬스터는 세지 않는다.
        List<Hostile> farAway = List.of(zombie(12), zombie(14), zombie(15), zombie(20), zombie(22), zombie(25),
                new Hostile(ThreatType.CREEPER, 18, false), new Hostile(ThreatType.CREEPER, 19, false),
                new Hostile(ThreatType.CREEPER, 21, false));
        assertFalse(combat.isOutnumbered(20, 20, ironGear, farAway));
        // 다쳤으면 더 적은 수에도 나서지 않는다.
        List<Hostile> few = List.of(zombie(5), zombie(7), zombie(9), new Hostile(ThreatType.SKELETON, 8, false));
        assertFalse(combat.isOutnumbered(20, 20, ironGear, few));
        assertTrue(combat.isOutnumbered(8, 20, ironGear, few));
    }

    // 회귀: 숨으려고 판 구덩이에 좀비가 따라 들어왔다. 달아나기가 "성공"으로 끝나기를 되풀이하는 20초 동안
    // 1초에 한 대씩 맞기만 하다가 죽었다. 달아나기 시작한 뒤에도 계속 맞고 있으면 벗어나지 못한 것이다.
    @Test
    void knowsWhenItKeepsGettingHitWhileRunningAway() {
        CombatMemory memory = new CombatMemory();
        // 싸우다가 맞은 것은 세지 않는다.
        memory.trackFleeing(false, 1000);
        memory.onHit(1000);
        memory.onHit(1020);
        memory.onHit(1040);
        assertEquals(0, memory.hitsWhileFleeing(1040, 100));
        memory.trackFleeing(true, 1050);
        assertEquals(0, memory.hitsWhileFleeing(1050, 100));

        memory.onHit(1060);
        memory.onHit(1080);
        memory.trackFleeing(true, 1090);
        assertEquals(2, memory.hitsWhileFleeing(1090, 100));
        memory.onHit(1100);
        assertEquals(3, memory.hitsWhileFleeing(1100, 100));
        // 오래전에 맞은 것은 세지 않는다.
        assertEquals(1, memory.hitsWhileFleeing(1190, 100));
        // 달아나기를 그만두면 처음부터 다시 센다.
        memory.trackFleeing(false, 1200);
        assertEquals(0, memory.hitsWhileFleeing(1200, 100));
        memory.trackFleeing(true, 1210);
        assertEquals(0, memory.hitsWhileFleeing(1210, 100));

        // 여러 번 맞아도 최근 것만 기억한다.
        for (int i = 0; i < 20; i++) memory.onHit(1220 + i);
        assertEquals(8, memory.hitsWhileFleeing(1240, 100));
        memory.reset();
        assertEquals(0, memory.hitsWhileFleeing(1240, 100));
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
