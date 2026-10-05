package me.herry.minecraftAI.ai.observation;

import java.util.Objects;

/**
 * 근처의 생물 하나. 위치는 AI 를 기준으로 한 상대 좌표다.
 *
 * @param type        EntityType 의 이름
 * @param hostile     몬스터로 분류된 것인지
 * @param targetingMe AI 를 노리고 있는지 (몬스터만 알 수 있다)
 */
public record EntitySummary(
        String type,
        double dx,
        double dy,
        double dz,
        double distance,
        double health,
        boolean hostile,
        boolean targetingMe
) {
    public EntitySummary {
        Objects.requireNonNull(type, "type");
    }
}
