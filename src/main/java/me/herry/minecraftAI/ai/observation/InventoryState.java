package me.herry.minecraftAI.ai.observation;

import java.util.Map;
import java.util.Objects;

/**
 * 가진 것. 개수는 "지금 가지고 있는 양"이고 가방, 손, 입은 것을 모두 더한 값이다.
 *
 * @param itemCounts      Material 이름별 개수
 * @param plankEquivalent 나무를 판자로 환산한 개수
 * @param stone           돌 도구의 재료가 되는 돌의 개수
 * @param coal            석탄과 숯의 개수
 * @param food            음식의 개수 (rawFood 포함)
 * @param pickaxeTier     가진 것 중 가장 좋은 곡괭이의 등급 (ToolTier 의 이름. 없으면 NONE). axeTier, swordTier 도 같다
 */
public record InventoryState(
        Map<String, Integer> itemCounts,
        int selectedSlot,
        int emptySlots,
        int junkSlots,
        int plankEquivalent,
        int stone,
        int coal,
        int food,
        int rawFood,
        boolean hasFuel,
        String pickaxeTier,
        String axeTier,
        String swordTier
) {
    public InventoryState {
        itemCounts = Map.copyOf(itemCounts);
        Objects.requireNonNull(pickaxeTier, "pickaxeTier");
        Objects.requireNonNull(axeTier, "axeTier");
        Objects.requireNonNull(swordTier, "swordTier");
    }

    /**
     * 그 아이템을 지금 몇 개 가지고 있는지. item 은 Material 이름이거나 ItemGroups 의 묶음 이름이다.
     * 모르는 이름이면 0 이다.
     */
    public int count(String item) {
        return switch (item) {
            case ItemGroups.WOOD -> plankEquivalent;
            case ItemGroups.STONE -> stone;
            case ItemGroups.COAL -> coal;
            case ItemGroups.FOOD -> food;
            default -> itemCounts.getOrDefault(item, 0);
        };
    }
}
