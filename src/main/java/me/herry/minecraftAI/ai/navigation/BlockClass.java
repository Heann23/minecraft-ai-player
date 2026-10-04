package me.herry.minecraftAI.ai.navigation;

/**
 * 경로 탐색 관점에서 본 블록 분류.
 */
public enum BlockClass {
    // 지나갈 수 있는 빈 공간 (공기, 풀, 꽃 등)
    OPEN,
    // 딛고 설 수 있고 통과할 수 없는 블록
    SOLID,
    // 헤엄쳐서 지나갈 수 있는 물
    WATER,
    // 용암, 불, 선인장처럼 닿으면 피해를 입는 블록
    DANGER,
    // 거미줄. 다치지는 않지만 그 안에서는 아주 느리게 움직인다. 검이나 가위로 베어 내고 지나가거나, 없으면 천천히 걸어서 지나간다
    WEB,
    // 울타리, 담장처럼 충돌 높이가 1.5칸이라 넘을 수도 딛을 수도 없는 블록
    FENCE,
    // 청크가 로드되지 않아 알 수 없는 영역
    UNLOADED
}
