package me.herry.minecraftAI.ai.memory;

/**
 * AI 가 위치와 함께 기억하는 정보의 종류.
 * 거점(집), 포탈, 상자 내용물처럼 오래 가는 구조화된 지식은 여기가 아니라 WorldModel 이 맡는다.
 */
public enum MemoryType {
    // 나무와 돌은 어디에나 있고 금방 다시 찾을 수 있어서 저장하지 않는다.
    TREE(false),
    STONE(false),
    COAL_ORE(true),
    IRON_ORE(true),
    DIAMOND_ORE(true),
    // 자갈. 캐면 가끔 부싯돌이 나온다. 흔해서 저장하지 않는다. 부싯돌을 얻으려고 직접 놓은 자갈도 여기에 적는다.
    GRAVEL(false),
    WORKBENCH(true),
    // 이 AI 가 직접 놓은 작업대. 다 쓰고 나면 다시 캐서 들고 다닌다. 남이 놓은 작업대는 가져가지 않는다.
    OWN_WORKBENCH(true),
    FURNACE(true),
    // 이 AI 가 직접 놓은 화로. 더 구울 것이 없으면 다시 캐서 들고 다닌다. 집 안에 들인 화로와 남이 놓은 화로는 가져가지 않는다.
    OWN_FURNACE(true),
    BED(true),
    DANGER_PLACE(true),
    // 아직 아무도 열지 않은 구조물의 전리품 상자
    LOOT_CHEST(true),
    // 가 보려 했지만 길이 없었던 곳. 같은 대상을 계속 다시 고르지 않기 위해 잠시 기억한다.
    UNREACHABLE(false),
    // 멀리서 나뭇잎이 보여서 가 본(가 보기로 한) 곳. 원목이 없는 덤불을 계속 다시 찾아가지 않기 위해 잠시 기억한다.
    CHECKED_PLACE(false);

    private final boolean persistent;

    MemoryType(boolean persistent) {
        this.persistent = persistent;
    }

    // 서버를 재시작해도 남겨 둘 기억인지
    public boolean isPersistent() {
        return persistent;
    }
}
