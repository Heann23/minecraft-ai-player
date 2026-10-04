package me.herry.minecraftAI.ai.perception;

/**
 * 위험 요소의 종류. danger 는 전투 판단에 쓰는 상대적 위험도이며 몬스터가 아닌 요소는 0 이다.
 */
public enum ThreatType {
    ZOMBIE(1.0),
    SKELETON(1.5),
    SPIDER(1.2),
    CREEPER(2.5),
    OTHER_HOSTILE(1.5),
    LAVA(0.0),
    FIRE(0.0),
    CLIFF(0.0),
    DROWNING(0.0),
    LOW_HEALTH(0.0),
    NIGHT(0.0);

    private final double danger;

    ThreatType(double danger) {
        this.danger = danger;
    }

    public double danger() {
        return danger;
    }

    public boolean isHostileMob() {
        return danger > 0.0;
    }
}
