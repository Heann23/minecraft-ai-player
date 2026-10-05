package me.herry.minecraftAI.config;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 설정값 사이의 관계를 검증한다. 값 하나하나의 범위는 AIConfig 가 clamp 로 맞추고,
 * 여기서는 "둘을 함께 놓고 봐야 알 수 있는" 잘못을 바로잡는다. Bukkit 에 의존하지 않는다.
 */
public final class ConfigRules {
    public record CombatRanges(double engageRange, double fleeDistance) {
    }

    // 도망친 뒤에 다시 교전 범위에 걸리지 않으려면 도망 거리가 교전 범위보다 이만큼은 커야 한다.
    public static final double MIN_FLEE_MARGIN = 4.0;
    public static final double MAX_FLEE_DISTANCE = 48.0;

    private ConfigRules() {
    }

    /**
     * 정해진 이름 중 하나여야 하는 값. 대소문자는 가리지 않는다. 목록에 없는 값이면 기본값으로 바꾸고 알린다.
     * (오타를 낸 채로 "켜 둔 줄 알고" 지내는 일을 막는다.)
     *
     * @param key 경고에 적을 설정 이름
     */
    public static String oneOf(String key, String value, List<String> allowed, String fallback, Consumer<String> warn) {
        String wanted = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (allowed.contains(wanted)) return wanted;
        warn.accept(key + " (" + value + ") is not one of " + allowed + ". Using " + fallback + ".");
        return fallback;
    }

    /**
     * 교전 범위와 도망 거리의 관계를 맞춘다.
     * 도망 거리가 교전 범위 이하이면, 도망을 끝낸 자리에서도 몬스터가 여전히 교전 범위 안이라
     * "도망 -> 다시 교전 판단 -> 도망"을 되풀이하게 된다.
     *
     * @param warn 값을 고쳤을 때 그 이유를 받는다
     */
    public static CombatRanges combatRanges(double engageRange, double fleeDistance, double entityRange, Consumer<String> warn) {
        double engage = engageRange;
        double flee = fleeDistance;

        // 인식하지 못하는 거리의 몬스터와는 교전할 수 없다.
        if (engage > entityRange) {
            warn.accept("combat.engage-range (" + format(engage) + ") 는 perception.entity-range (" + format(entityRange)
                    + ") 보다 클 수 없어서 " + format(entityRange) + " 로 낮췄습니다.");
            engage = entityRange;
        }
        if (flee < engage + MIN_FLEE_MARGIN) {
            double fixed = Math.min(MAX_FLEE_DISTANCE, engage + MIN_FLEE_MARGIN);
            warn.accept("combat.flee-distance (" + format(flee) + ") 는 combat.engage-range (" + format(engage) + ") 보다 "
                    + format(MIN_FLEE_MARGIN) + " 이상 커야 해서 " + format(fixed) + " 로 올렸습니다.");
            flee = fixed;
        }
        // 도망 거리를 한도까지 올려도 모자라면 교전 범위를 줄인다.
        if (flee < engage + MIN_FLEE_MARGIN) {
            double fixed = flee - MIN_FLEE_MARGIN;
            warn.accept("combat.engage-range (" + format(engage) + ") 가 너무 커서 " + format(fixed) + " 로 낮췄습니다.");
            engage = fixed;
        }
        return new CombatRanges(engage, flee);
    }

    /**
     * 한 틱에 펼치는 노드 수가 경로 하나의 한도보다 크면 의미가 없다 (한 틱에 탐색 전체가 끝나 버려 예산 분할이 무력해진다).
     */
    public static int nodesPerTick(int nodesPerTick, int maxPathNodes, Consumer<String> warn) {
        if (nodesPerTick <= maxPathNodes) return nodesPerTick;
        warn.accept("navigation.nodes-per-tick (" + nodesPerTick + ") 는 navigation.max-path-nodes (" + maxPathNodes
                + ") 보다 클 수 없어서 " + maxPathNodes + " 로 낮췄습니다.");
        return maxPathNodes;
    }

    /**
     * 위급 체력은 낮은 체력보다 클 수 없다. 크면 "낮음"을 건너뛰고 바로 "위급"이 된다.
     */
    public static int criticalHealth(int criticalHealth, int lowHealth, Consumer<String> warn) {
        if (criticalHealth <= lowHealth) return criticalHealth;
        warn.accept("survival.critical-health (" + criticalHealth + ") 는 survival.low-health (" + lowHealth
                + ") 보다 클 수 없어서 " + lowHealth + " 로 낮췄습니다.");
        return lowHealth;
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
