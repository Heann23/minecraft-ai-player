package me.herry.minecraftAI.ai.observation;

import java.util.Map;
import java.util.Objects;

/**
 * 기억하고 있는 것. 지금 보이는 것이 아니라 전에 보고 적어 둔 것이다.
 *
 * @param homeDistance   거점까지의 수평 거리. 거점을 모르면 뜻이 없다 (homeKnown 을 먼저 본다)
 * @param canSleep       거점에 침대가 있고 지금 잘 수 있는 시간인지
 * @param knownPlaces    기억 종류(MemoryType 의 이름)별로 이 월드에서 기억하는 곳의 수. 하나도 없는 종류는 들어 있지 않다
 * @param recentFailures 기억에 남아 있는 최근 행동 실패의 수
 * @param lastGoal       직전에 하던 목표 (GoalType 의 이름). 없으면 빈 문자열
 */
public record MemoryState(
        boolean homeKnown,
        double homeDistance,
        boolean insideHome,
        boolean homeSheltered,
        boolean chestAvailable,
        boolean canSleep,
        boolean knowsTree,
        boolean knowsCoal,
        boolean knowsIron,
        boolean knowsDiamond,
        boolean knowsLootChest,
        boolean furnaceBusy,
        Map<String, Integer> knownPlaces,
        int recentFailures,
        String lastGoal
) {
    public MemoryState {
        knownPlaces = Map.copyOf(knownPlaces);
        Objects.requireNonNull(lastGoal, "lastGoal");
    }
}
