package me.herry.minecraftAI.ai.combat;

import me.herry.minecraftAI.ai.perception.ThreatType;

import java.util.List;

/**
 * 몬스터를 만났을 때 싸울지 도망갈지 판단한다. 입력이 단순한 값뿐이라 서버 없이도 테스트할 수 있다.
 */
public final class CombatSystem {
    public enum Decision { NONE, FIGHT, FLEE }

    /**
     * 판단에 필요한 몬스터 한 마리의 정보.
     */
    public record Hostile(ThreatType type, double distance, boolean targetingMe) {
    }

    // 크리퍼는 가까이서 싸우면 폭발하므로, 폭발을 막거나 피하면서 싸울 방법이 없으면 이 거리 안에 들어왔을 때 무조건 물러난다.
    private static final double CREEPER_FLEE_RANGE = 5.0;
    // 치고 물러나기를 되풀이해서 크리퍼를 잡으려면 몇 번 안에 잡을 무기(돌 도끼나 돌 검 이상)가 있어야 한다.
    private static final double HIT_AND_RUN_WEAPON = 1.5;
    // 숨을 자리를 막으려면 들어온 쪽의 두 칸을 채울 블록이 있어야 한다.
    private static final int REFUGE_BLOCKS = 2;
    // 체력이 가득하고 무기 공격력이 1.0 일 때 감당할 수 있는 위험도의 합.
    private static final double STRENGTH_SCALE = 3.0;
    private static final double MAX_ARMOR_POINTS = 20.0;
    private static final double ARMOR_WEIGHT = 0.8;
    // 허기가 이 값 이하면 달릴 수 없고 체력도 회복되지 않는다.
    private static final int STARVING_FOOD = 6;
    private static final double STARVING_PENALTY = 0.7;

    private final double engageRange;
    private final double criticalHealth;

    public CombatSystem(double engageRange, double criticalHealth) {
        this.engageRange = engageRange;
        this.criticalHealth = criticalHealth;
    }

    /**
     * 교전 범위 안의 몬스터만 상대로 본다. 멀리서 쫓아오는 몬스터는 범위 안에 들어왔을 때 대응한다.
     *
     * @param weaponPower 가진 무기의 상대적 공격력 (맨손 0.5, 나무 1.0, 돌 1.4 ...)
     */
    public Decision decide(double health, double maxHealth, double weaponPower, List<Hostile> hostiles) {
        return decide(health, maxHealth, weaponPower, hostiles, false);
    }

    /**
     * @param canFaceBlast 크리퍼를 가까이에서 상대할 방법이 있는지 (방패로 폭발을 막거나, 치고 물러나기를 되풀이하거나).
     *                     있으면 크리퍼가 가까이 와도 무조건 물러나지 않고 다른 몬스터처럼 감당할 수 있는지를 따진다.
     */
    public Decision decide(double health, double maxHealth, double weaponPower, List<Hostile> hostiles, boolean canFaceBlast) {
        double threat = 0.0;
        boolean engaged = false;
        for (Hostile hostile : hostiles) {
            if (!hostile.type().isHostileMob()) continue;
            boolean blast = hostile.type() == ThreatType.CREEPER && hostile.distance() <= CREEPER_FLEE_RANGE;
            if (blast && !canFaceBlast) return Decision.FLEE;
            if (hostile.distance() <= engageRange) {
                threat += hostile.type().danger();
                engaged = true;
            }
        }
        if (!engaged) return Decision.NONE;

        if (health <= criticalHealth) return Decision.FLEE;
        return threat > strength(health, maxHealth, weaponPower) ? Decision.FLEE : Decision.FIGHT;
    }

    /**
     * 방패 없이도 크리퍼를 상대할 수 있는지. 한 대 치고, 부풀면 뒷걸음으로 물러났다가, 부풀기를 멈추면 다시 친다.
     *
     * @param weaponPower            갑옷과 허기를 반영하지 않은 무기 자체의 공격력
     * @param retreatBlockedRecently 방금 물러날 자리가 없어서 물러나지 못했는지 (좁은 굴, 낭떠러지 앞 등)
     */
    public static boolean canHitAndRun(double weaponPower, boolean retreatBlockedRecently) {
        return weaponPower >= HIT_AND_RUN_WEAPON && !retreatBlockedRecently;
    }

    /**
     * 달아나는 대신 벽이나 땅을 파고 들어가 숨을지. 땅속에서는 길이 좁고 막다른 곳이 많아서 달아나도 따라잡히기 쉽다.
     * 지상에서도 방금 달아나지 못했으면 숨는다.
     *
     * @param fillerBlocks 숨은 자리를 막는 데 쓸 수 있는 블록(조약돌, 흙 등)의 개수
     */
    public static boolean shouldTakeRefuge(boolean underground, boolean fleeFailedRecently, int fillerBlocks) {
        return (underground || fleeFailedRecently) && fillerBlocks >= REFUGE_BLOCKS;
    }

    public static double strength(double health, double maxHealth, double weaponPower) {
        double healthRatio = maxHealth <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, health / maxHealth));
        return healthRatio * weaponPower * STRENGTH_SCALE;
    }

    /**
     * 갑옷과 허기를 반영해서 무기 공격력에 곱하는 값.
     * 갑옷이 좋을수록 같은 무기로도 더 많은 상대를 감당할 수 있고(철 갑옷 한 벌이면 1.6배),
     * 굶주려서 달릴 수도 회복할 수도 없으면 덜 감당한다.
     *
     * @param armorPoints 방어력 수치 (갑옷 없음 0, 철 한 벌 15, 다이아몬드 한 벌 20)
     * @param food        허기 수치 (0 ~ 20)
     */
    public static double readiness(double armorPoints, int food) {
        double armor = Math.max(0.0, Math.min(MAX_ARMOR_POINTS, armorPoints));
        double factor = 1.0 + armor / MAX_ARMOR_POINTS * ARMOR_WEIGHT;
        return food <= STARVING_FOOD ? factor * STARVING_PENALTY : factor;
    }
}
