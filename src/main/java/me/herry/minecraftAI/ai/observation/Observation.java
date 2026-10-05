package me.herry.minecraftAI.ai.observation;

import java.util.Objects;

/**
 * 판단하는 순간에 AI 가 알고 있던 것 전부를 값으로만 담은 스냅샷.
 * Bukkit / Paper / NMS 객체를 들고 있지 않아서, 만든 뒤에는 어느 스레드에서 읽어도 되고 파일로 남길 수 있다.
 * 목표의 완료 조건(Goal.isAchieved)이 이것만 보고 판정하고, 나중에 경험 기록과 학습 정책의 입력이 된다.
 *
 * 필드를 더하거나 뜻을 바꾸면 SCHEMA_VERSION 을 올린다. 기록해 둔 데이터가 어느 모양인지 그 번호로 가린다.
 *
 * @param tick AI 가 켜진 뒤로 센 틱 (AIPlayer.getTicks). 판단한 시각이다.
 * @param task 이 판단을 하기 직전에 하고 있던 일. 이번 판단으로 고른 목표가 아니다.
 */
public record Observation(
        int schemaVersion,
        long tick,
        PlayerState player,
        InventoryState inventory,
        EnvironmentState environment,
        ProgressState progress,
        MemoryState memory,
        CurrentTaskState task
) {
    public static final int SCHEMA_VERSION = 1;

    public Observation {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(task, "task");
    }
}
