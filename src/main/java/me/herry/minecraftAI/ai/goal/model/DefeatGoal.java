package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.observation.EnvironmentState;
import me.herry.minecraftAI.ai.observation.Observation;

import java.util.Objects;

/**
 * 상대를 물리친다.
 *
 * 지금 완료를 판정할 수 있는 대상은 셋이다.
 *   HOSTILE      지금 상대해야 하는 몬스터 (가까이에 남아 있지 않고 전투 판단이 없으면 이룬 것)
 *   ALLY_THREAT  도움을 청한 동료를 공격하는 몬스터 (도움 요청이 없어지면 이룬 것)
 *   ENDER_DRAGON 엔더 드래곤 (처치 기록이 있으면 이룬 것)
 * 그 밖의 대상(EntityType 이름)은 잡은 수를 아직 세지 않으므로 isAchieved 가 늘 false 다.
 *
 * @param target 위의 이름, 또는 EntityType 의 이름
 * @param count  잡을 수. HOSTILE 과 ALLY_THREAT 은 "남아 있지 않을 때까지"라서 쓰지 않는다.
 */
public record DefeatGoal(String target, int count, GoalMetadata metadata) implements Goal {
    public static final String HOSTILE = "HOSTILE";
    public static final String ALLY_THREAT = "ALLY_THREAT";
    public static final String ENDER_DRAGON = "ENDER_DRAGON";

    public DefeatGoal {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(metadata, "metadata");
        if (count < 1) throw new IllegalArgumentException("count must be at least 1: " + count);
    }

    public DefeatGoal(String target, int count) {
        this(target, count, GoalMetadata.NONE);
    }

    @Override
    public GoalCategory category() {
        return GoalCategory.DEFEAT;
    }

    @Override
    public String describe() {
        return "Defeat(" + target + " x" + count + ")";
    }

    @Override
    public boolean isAchieved(Observation observation) {
        EnvironmentState environment = observation.environment();
        return switch (target) {
            case HOSTILE -> !environment.hostileNearby() && environment.combatDecision().equals(EnvironmentState.NO_COMBAT);
            case ALLY_THREAT -> !environment.allyNeedsHelp();
            case ENDER_DRAGON -> observation.progress().dragonDefeated();
            default -> false;
        };
    }
}
