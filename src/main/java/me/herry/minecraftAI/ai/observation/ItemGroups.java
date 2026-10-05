package me.herry.minecraftAI.ai.observation;

/**
 * 아이템 하나가 아니라 "같은 쓰임의 것들"을 가리키는 이름. 목표의 대상(AcquireGoal 의 item)으로 쓴다.
 * Material 이름과 겹치지 않도록 # 로 시작한다.
 */
public final class ItemGroups {
    // 나무. 개수는 판자로 환산한다 (원목 1개 = 판자 4개, 막대 2개 = 판자 1개).
    public static final String WOOD = "#WOOD";
    // 돌 도구의 재료가 되는 돌 (조약돌, 심층암 조약돌, 흑암)
    public static final String STONE = "#STONE";
    // 석탄과 숯
    public static final String COAL = "#COAL";
    // 먹을 수 있는 음식 (날것 포함)
    public static final String FOOD = "#FOOD";

    private ItemGroups() {
    }

    public static boolean isGroup(String item) {
        return item.startsWith("#");
    }
}
