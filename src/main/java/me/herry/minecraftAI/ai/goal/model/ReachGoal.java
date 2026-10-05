package me.herry.minecraftAI.ai.goal.model;

import me.herry.minecraftAI.ai.observation.Observation;
import me.herry.minecraftAI.ai.observation.PlayerState;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 어떤 곳에 가 있는다.
 *
 * @param place     가려는 곳의 종류
 * @param point     place 가 POSITION 일 때의 좌표 (지금 있는 차원의 좌표). 그 밖에는 null
 * @param tolerance 이 거리 안에 들면 도착한 것으로 본다. HOME 은 거점까지의 수평 거리, POSITION 은 그 좌표까지의 거리다.
 *                  차원은 들어가 있기만 하면 되므로 쓰지 않는다.
 */
public record ReachGoal(Place place, @Nullable BlockPoint point, double tolerance, GoalMetadata metadata) implements Goal {
    public enum Place {
        // 거점. 집 안에 들어가 있어도 도착한 것이다.
        HOME,
        POSITION,
        OVERWORLD,
        NETHER,
        THE_END
    }

    public ReachGoal {
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(metadata, "metadata");
        if (place == Place.POSITION && point == null) throw new IllegalArgumentException("POSITION needs a point");
        if (tolerance < 0.0) throw new IllegalArgumentException("tolerance must not be negative: " + tolerance);
    }

    public static ReachGoal home(double tolerance) {
        return new ReachGoal(Place.HOME, null, tolerance, GoalMetadata.NONE);
    }

    public static ReachGoal position(BlockPoint point, double tolerance) {
        return new ReachGoal(Place.POSITION, point, tolerance, GoalMetadata.NONE);
    }

    public static ReachGoal dimension(Place place) {
        if (place == Place.HOME || place == Place.POSITION) throw new IllegalArgumentException(place + " is not a dimension");
        return new ReachGoal(place, null, 0.0, GoalMetadata.NONE);
    }

    @Override
    public GoalCategory category() {
        return GoalCategory.REACH;
    }

    @Override
    public String describe() {
        return place == Place.POSITION ? "Reach(" + point + " within " + tolerance + ")" : "Reach(" + place + ")";
    }

    @Override
    public boolean isAchieved(Observation observation) {
        PlayerState player = observation.player();
        return switch (place) {
            case HOME -> observation.memory().homeKnown()
                    && (observation.memory().insideHome() || observation.memory().homeDistance() <= tolerance);
            case POSITION -> {
                double dx = player.x() - (point.x() + 0.5);
                double dy = player.y() - point.y();
                double dz = player.z() - (point.z() + 0.5);
                yield Math.sqrt(dx * dx + dy * dy + dz * dz) <= tolerance;
            }
            case OVERWORLD -> player.dimension().equals(PlayerState.OVERWORLD);
            case NETHER -> player.dimension().equals(PlayerState.NETHER);
            case THE_END -> player.dimension().equals(PlayerState.THE_END);
        };
    }

    // 돌아갈 거점이 없으면 거점으로 갈 수 없다.
    @Override
    public boolean canAttempt(Observation observation) {
        return place != Place.HOME || observation.memory().homeKnown();
    }
}
