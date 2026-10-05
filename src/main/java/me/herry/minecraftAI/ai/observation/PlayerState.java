package me.herry.minecraftAI.ai.observation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 의 몸 상태.
 *
 * @param dimension   OVERWORLD, NETHER, THE_END 중 하나 (그 밖의 월드는 CUSTOM)
 * @param healthState SurvivalSystem.HealthState 의 이름
 * @param hungry      지금 먹어야 하는지 (SurvivalSystem.shouldEat)
 * @param armor       방어력 수치 (갑옷 아이콘 반 칸이 1)
 * @param equipment   부위별로 입거나 든 것의 Material 이름. 키는 HEAD, CHEST, LEGS, FEET, MAIN_HAND, OFF_HAND 이고 빈 부위는 들어 있지 않다
 * @param effects     걸려 있는 효과의 이름
 */
public record PlayerState(
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String dimension,
        double health,
        double maxHealth,
        String healthState,
        int food,
        float saturation,
        boolean hungry,
        int air,
        int maxAir,
        double armor,
        boolean grounded,
        boolean inWater,
        boolean inLava,
        boolean onFire,
        boolean standingInDanger,
        boolean suffocating,
        boolean drowning,
        Map<String, String> equipment,
        List<String> effects
) {
    public static final String OVERWORLD = "OVERWORLD";
    public static final String NETHER = "NETHER";
    public static final String THE_END = "THE_END";
    public static final String CUSTOM = "CUSTOM";
    public static final String HEALTH_OK = "OK";

    public PlayerState {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(healthState, "healthState");
        equipment = Map.copyOf(equipment);
        effects = List.copyOf(effects);
    }

    // 용암, 불, 질식, 익사처럼 그대로 있으면 죽는 자리에 있는지
    public boolean inHazard() {
        return inLava || standingInDanger || suffocating || drowning;
    }
}
