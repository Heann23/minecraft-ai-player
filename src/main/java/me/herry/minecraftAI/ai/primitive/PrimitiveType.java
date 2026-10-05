package me.herry.minecraftAI.ai.primitive;

/**
 * 월드와 상호작용하는 가장 작은 행동의 종류. 스킬(과 나중의 학습 정책)은 이 종류의 행동을 조합해서 일을 한다.
 *
 * 여기에 있는 종류라고 해서 모든 쓰임이 구현되어 있는 것은 아니다. 지금 실제로 되는 것은 다음과 같다.
 *   USE_ITEM        음식 먹기만 된다. 양동이, 부싯돌과 부시, 엔더의 눈, 활은 아직 없다.
 *   INTERACT_BLOCK  상자(넣기, 꺼내기, 전리품), 화로(넣기, 꺼내기), 침대만 된다.
 *   INTERACT_ENTITY 동료 AI 에게 음식 건네기만 된다.
 *   DROP_ITEM       쓰지 않는 아이템 버리기만 된다. 고른 아이템을 버리는 것은 아직 없다.
 * 탐험, 달아나기, 굴 따라가기처럼 여러 종류를 섞어 쓰는 행동은 PrimitiveAction 이 아니라 그냥 Action 이다.
 */
public enum PrimitiveType {
    MOVE_TO,
    LOOK_AT,
    JUMP,
    BREAK_BLOCK,
    PLACE_BLOCK,
    USE_ITEM,
    INTERACT_BLOCK,
    INTERACT_ENTITY,
    ATTACK,
    PICKUP_ITEM,
    DROP_ITEM,
    EQUIP_ITEM,
    SELECT_SLOT,
    CRAFT_ITEM,
    WAIT
}
