package me.herry.minecraftAI.ai.build;

/**
 * 설계도의 한 칸이 맡는 역할. 어떤 블록으로 채울지는 지을 때 가진 재료를 보고 정한다.
 */
public enum BlockRole {
    // 비워야 하는 칸 (실내 공간). 무언가 있으면 캐낸다.
    CLEAR(false, false),
    // 바닥. 이미 단단한 땅이면 그대로 두고, 구멍이면 메운다.
    FLOOR(true, true),
    WALL(true, true),
    ROOF(true, true),
    // 네더 포탈의 틀. 흑요석으로만 채운다.
    FRAME(false, true),
    DOOR(false, true),
    WORKBENCH(false, true),
    CHEST(false, true),
    // 아래는 재료가 없으면 건너뛰어도 집으로 쓸 수 있는 것들이다. 나중에 재료가 생기면 채운다.
    FURNACE(false, false),
    TORCH(false, false),
    BED(false, false);

    private final boolean structural;
    private final boolean required;

    BlockRole(boolean structural, boolean required) {
        this.structural = structural;
        this.required = required;
    }

    // 돌이나 흙 같은 흔한 블록으로 채우는 칸인지 (벽, 지붕, 바닥)
    public boolean isStructural() {
        return structural;
    }

    // 이것이 없으면 집이 완성된 것으로 치지 않는지
    public boolean isRequired() {
        return required;
    }
}
