package me.herry.minecraftAI.ai.comm;

/**
 * 사람의 말에서 읽어 낸 의도. 언어 해석기(규칙 기반이든 LLM 이든)는 말을 이 형태로만 바꾸고,
 * 그 의도를 실제 행동으로 옮기는 일은 AI 쪽(ConversationHandler)이 맡는다.
 *
 * @param subject 의도의 대상. GATHER 에서는 자원 종류, ASK_HAVE 에서는 아이템 종류다. 대상이 없으면 NONE.
 */
public record Intent(Type type, Subject subject) {
    public enum Type {
        // AI 에게 한 말이 아니거나 무슨 뜻인지 알 수 없음
        NONE,
        // "지금 뭐 하고 있어?"
        ASK_STATUS,
        // "왜 그거 하고 있어?"
        ASK_REASON,
        // "다이아 찾았어?", "철 몇 개 있어?"
        ASK_HAVE,
        // "어디야?"
        ASK_LOCATION,
        // "집으로 돌아가"
        GO_HOME,
        // "철 좀 구해와"
        GATHER,
        // "멈춰", "그만"
        STOP,
        // "다시 움직여", "계속해"
        RESUME,
        // "알아서 해" - 시킨 일을 취소하고 스스로 판단하게 한다
        AUTONOMOUS,
        // 알아들었지만 아직 할 줄 모르는 요청 (예: "네더 준비부터 하자")
        UNSUPPORTED
    }

    public enum Subject {
        NONE, WOOD, STONE, COAL, IRON, DIAMOND, FOOD
    }

    public static final Intent NONE = new Intent(Type.NONE, Subject.NONE);

    public static Intent of(Type type) {
        return new Intent(type, Subject.NONE);
    }
}
