package me.herry.minecraftAI.ai.survival;

/**
 * 체력, 허기, 밤에 대한 생존 판단. 입력이 단순한 값뿐이라 서버 없이도 테스트할 수 있다.
 */
public final class SurvivalSystem {
    public enum HealthState { OK, LOW, CRITICAL }

    public enum NightPolicy {
        // 장비가 충분해서 밤에도 하던 일을 계속한다
        CONTINUE,
        // 장비가 부족해서 거점 근처에 머문다
        SHELTER
    }

    // 돌 등급 무기(공격력 1.4) 이상이면 밤에도 활동한다.
    private static final double NIGHT_ACTIVE_POWER = 1.4;
    // 자연 회복은 허기가 18 이상일 때만 일어난다.
    private static final int REGEN_FOOD_LEVEL = 18;
    private static final int MAX_FOOD_LEVEL = 20;

    private final int lowHealth;
    private final int criticalHealth;
    private final int eatBelow;

    public SurvivalSystem(int lowHealth, int criticalHealth, int eatBelow) {
        this.lowHealth = lowHealth;
        this.criticalHealth = criticalHealth;
        this.eatBelow = eatBelow;
    }

    public HealthState healthState(double health) {
        if (health <= criticalHealth) return HealthState.CRITICAL;
        if (health <= lowHealth) return HealthState.LOW;
        return HealthState.OK;
    }

    // 배가 고프거나, 다쳤는데 허기 때문에 회복이 안 되는 상태면 먹어야 한다.
    public boolean shouldEat(int food, double health, double maxHealth) {
        if (food >= MAX_FOOD_LEVEL) return false;
        return food < eatBelow || (health < maxHealth && food < REGEN_FOOD_LEVEL);
    }

    // 굶어서 체력이 깎이기 직전인지
    public boolean isStarving(int food) {
        return food <= 6;
    }

    public boolean canRegenerate(int food) {
        return food >= REGEN_FOOD_LEVEL;
    }

    public NightPolicy nightPolicy(double weaponPower) {
        return weaponPower >= NIGHT_ACTIVE_POWER ? NightPolicy.CONTINUE : NightPolicy.SHELTER;
    }
}
