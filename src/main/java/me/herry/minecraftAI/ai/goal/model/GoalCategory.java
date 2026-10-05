package me.herry.minecraftAI.ai.goal.model;

/**
 * 범용 목표의 큰 갈래.
 */
public enum GoalCategory {
    // 아이템을 정한 개수만큼 가진다
    ACQUIRE,
    // 아이템을 만들어서 가진다
    CRAFT,
    // 어떤 곳(거점, 좌표, 차원)에 가 있는다
    REACH,
    // 상대를 물리친다
    DEFEAT,
    // 구조물을 짓는다
    BUILD,
    // 블록이나 엔티티를 써서 하는 일 (잠, 상자)
    INTERACT,
    EXPLORE,
    // 당장 죽지 않기 위한 일
    SURVIVE,
    // 아직 "이루려는 상태"로 적지 못한 기존 목표 (가방 정리, 화로 챙기기 등)
    LEGACY
}
