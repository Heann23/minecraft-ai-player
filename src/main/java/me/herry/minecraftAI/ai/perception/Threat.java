package me.herry.minecraftAI.ai.perception;

import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 감지된 위험 요소 하나.
 *
 * @param entity      몬스터인 경우 해당 엔티티, 지형이나 상태에서 온 위험이면 null
 * @param distance    AI 로부터의 거리. 거리 개념이 없는 위험(낮은 체력, 밤)은 0
 * @param targetingMe 몬스터가 AI 를 공격 대상으로 삼고 있는지
 */
public record Threat(ThreatType type, @Nullable LivingEntity entity, double distance, boolean targetingMe) {
    public static Threat of(ThreatType type, double distance) {
        return new Threat(type, null, distance, false);
    }
}
