package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.observation.EnvironmentState;
import me.herry.minecraftAI.ai.observation.Observation;
import me.herry.minecraftAI.ai.observation.PlayerState;

import java.util.Objects;

/**
 * 당장 죽지 않기 위한 일.
 */
public record SurviveGoal(Reason reason, GoalMetadata metadata) implements Goal {
    public enum Reason {
        // 용암, 불, 질식, 익사에서 벗어나고, 감당할 수 없는 몬스터에게서 달아난다
        ESCAPE_DANGER,
        // 체력이 돌아올 때까지 싸움을 피하고 회복한다
        RECOVER_HEALTH,
        // 배를 채운다
        EAT
    }

    public SurviveGoal {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(metadata, "metadata");
    }

    public SurviveGoal(Reason reason) {
        this(reason, GoalMetadata.NONE);
    }

    @Override
    public GoalCategory category() {
        return GoalCategory.SURVIVE;
    }

    @Override
    public String describe() {
        return "Survive(" + reason + ")";
    }

    @Override
    public boolean isAchieved(Observation observation) {
        PlayerState player = observation.player();
        return switch (reason) {
            case ESCAPE_DANGER -> !player.inHazard() && !observation.environment().combatDecision().equals(EnvironmentState.FLEE);
            case RECOVER_HEALTH -> player.healthState().equals(PlayerState.HEALTH_OK);
            case EAT -> !player.hungry();
        };
    }
}
