package me.herry.minecraftAI.ai.policy;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * 설정에 적은 이름으로 후보 정책을 만든다. 여기에 있는 것만 쓸 수 있다.
 * 학습한 모델을 붙일 때 이름을 하나 더한다.
 */
public final class GoalPolicies {
    public static final String NONE = "none";
    public static final List<String> NAMES = List.of(NONE, RepeatLastGoalPolicy.ID);

    private GoalPolicies() {
    }

    public static boolean isKnown(String name) {
        return NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    // 후보 정책을 쓰지 않거나(none) 모르는 이름이면 null.
    public static @Nullable GoalPolicy create(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case RepeatLastGoalPolicy.ID -> new RepeatLastGoalPolicy();
            default -> null;
        };
    }
}
