package me.herry.minecraftAI.ai.observation;

import java.util.List;
import java.util.Objects;

/**
 * 주변 환경과 지금 닥친 위협.
 *
 * @param timeOfDay      월드 시각 (0~23999)
 * @param biome          서 있는 곳의 생물 군계 키
 * @param lightLevel     서 있는 칸의 밝기 (0~15)
 * @param underground    깊은 땅속이나 구덩이 안이라 지상의 일을 하려면 먼저 올라가야 하는지
 * @param sealedIn       사방과 위가 막힌 자리에 숨어 있는지
 * @param hostileNearby  달아나야 할 만큼 가까이에, 상대해야 하는 몬스터가 있는지
 * @param combatDecision 전투 판단 (CombatSystem.Decision 의 이름)
 * @param hostileCount   감지된 몬스터 수 (벽 너머 포함). animalCount, dropCount 도 감지 범위 안의 수다
 * @param entities       가까운 순으로 추린 생물 목록 (최대 MAX_ENTITIES)
 */
public record EnvironmentState(
        long timeOfDay,
        boolean night,
        boolean storm,
        boolean thundering,
        String biome,
        int lightLevel,
        boolean underground,
        boolean sealedIn,
        boolean lavaNearby,
        boolean cliffAhead,
        boolean hostileNearby,
        String combatDecision,
        boolean allyNeedsHelp,
        int hostileCount,
        int animalCount,
        int dropCount,
        List<EntitySummary> entities
) {
    public static final int MAX_ENTITIES = 16;
    public static final String NO_COMBAT = "NONE";
    public static final String FLEE = "FLEE";

    public EnvironmentState {
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(combatDecision, "combatDecision");
        entities = List.copyOf(entities);
    }
}
