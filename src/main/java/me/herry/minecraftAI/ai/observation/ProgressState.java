package me.herry.minecraftAI.ai.observation;

import java.util.List;
import java.util.Objects;

/**
 * 엔더 드래곤까지의 진행 상황.
 *
 * @param stage              지금 속한 큰 단계 (Stage 의 이름)
 * @param nextMilestone      다음에 이룰 것 (Milestone 의 이름). 전부 이뤘으면 빈 문자열
 * @param need               다음 것에 지금 부족한 재료의 종류 (Situation.Need 의 이름)
 * @param achievedMilestones 이미 이룬 항목들 (Milestone 의 이름, 정의된 순서)
 * @param tableAvailable     작업대가 가방에 있거나 다시 찾아갈 만큼 가까이에 놓여 있는지. furnaceAvailable 도 같다
 */
public record ProgressState(
        String stage,
        String nextMilestone,
        String need,
        List<String> achievedMilestones,
        boolean tableAvailable,
        boolean furnaceAvailable,
        boolean shelterBuilt,
        boolean netherPortalBuilt,
        boolean strongholdFound,
        boolean endPortalReady,
        boolean dragonDefeated
) {
    public ProgressState {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(nextMilestone, "nextMilestone");
        Objects.requireNonNull(need, "need");
        achievedMilestones = List.copyOf(achievedMilestones);
    }
}
