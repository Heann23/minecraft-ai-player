package me.herry.minecraftAI.ai.comm;

import java.util.Locale;

/**
 * 정해진 낱말을 찾아서 의도를 읽어 내는 규칙 기반 해석기. 네트워크도 모델도 쓰지 않는다.
 * 자유로운 문장을 다 알아듣지는 못하지만, 자주 쓰는 질문과 부탁은 처리한다. 알아듣지 못하면 Intent.NONE 을 돌려준다.
 */
public final class KeywordInterpreter implements LanguageInterpreter {
    private static final String[] STOP = {"멈춰", "멈춰줘", "그만", "정지", "stop"};
    private static final String[] TEASING = {"장난", "농담", "놀리", "놀려"};
    private static final String[] RESUME ={"다시 움직", "다시 시작", "계속해", "움직여", "resume"};
    private static final String[] AUTONOMOUS = {"알아서", "하던 거", "하던거", "자유롭게", "마음대로", "auto"};
    private static final String[] HOME = {"집", "거점", "home"};
    // "집에 뭐가 있어?" 같은 질문을 귀환 명령으로 읽지 않도록, 조사까지 붙은 꼴만 본다.
    private static final String[] COME_BACK = {"돌아", "복귀", "으로 가", "에 가", "로 가", "으로 와", "에 와", "로 와", "가자", "return", "go ", "back"};
    private static final String[] REASON = {"왜", "이유", "why"};
    private static final String[] LOCATION = {"어디", "위치", "where"};
    private static final String[] HAVE = {"찾았", "구했", "있어", "있니", "있냐", "몇 개", "몇개", "얼마나", "have"};
    private static final String[] FETCH = {"구해", "캐", "가져", "모아", "찾아", "베어", "사냥", "get", "mine", "gather"};
    private static final String[] STATUS = {"뭐 해", "뭐해", "뭐 하", "뭐하", "뭘 하", "뭘 해", "무엇을", "상태", "status", "doing"};
    private static final String[] BEYOND = {"네더", "엔드", "드래곤", "포탈", "nether", "dragon", "portal"};

    @Override
    public Intent interpret(IncomingMessage message) {
        String text = message.text().toLowerCase(Locale.ROOT).trim();
        if (text.isEmpty()) return Intent.NONE;

        // "장난 그만해" 는 말버릇을 고쳐 달라는 뜻이지 하던 일을 멈추라는 뜻이 아니다.
        if (containsAny(text, STOP) && !containsAny(text, TEASING)) return Intent.of(Intent.Type.STOP);
        if (containsAny(text, AUTONOMOUS)) return Intent.of(Intent.Type.AUTONOMOUS);
        if (containsAny(text, RESUME)) return Intent.of(Intent.Type.RESUME);
        if (containsAny(text, HOME) && containsAny(text, COME_BACK)) return Intent.of(Intent.Type.GO_HOME);
        if (containsAny(text, REASON)) return Intent.of(Intent.Type.ASK_REASON);

        Intent.Subject subject = subjectOf(text);
        if (subject != Intent.Subject.NONE) {
            // "다이아 찾았어?" 는 질문이고 "다이아 찾아와" 는 부탁이다. 질문을 먼저 본다.
            if (containsAny(text, HAVE)) return new Intent(Intent.Type.ASK_HAVE, subject);
            if (containsAny(text, FETCH)) return new Intent(Intent.Type.GATHER, subject);
        }
        if (containsAny(text, LOCATION)) return Intent.of(Intent.Type.ASK_LOCATION);
        if (containsAny(text, STATUS)) return Intent.of(Intent.Type.ASK_STATUS);
        if (containsAny(text, BEYOND)) return Intent.of(Intent.Type.UNSUPPORTED);
        return Intent.NONE;
    }

    private static Intent.Subject subjectOf(String text) {
        // "돌아가", "돌아와" 의 "돌" 을 돌(자원)로 읽지 않도록 먼저 지운다.
        String cleaned = text.replace("돌아", "");
        if (containsAny(cleaned, "다이아", "diamond")) return Intent.Subject.DIAMOND;
        if (containsAny(cleaned, "석탄", "coal")) return Intent.Subject.COAL;
        if (containsAny(cleaned, "철", "iron")) return Intent.Subject.IRON;
        if (containsAny(cleaned, "나무", "원목", "wood", "log")) return Intent.Subject.WOOD;
        if (containsAny(cleaned, "음식", "먹을", "식량", "고기", "food")) return Intent.Subject.FOOD;
        if (containsAny(cleaned, "조약돌", "돌", "stone")) return Intent.Subject.STONE;
        return Intent.Subject.NONE;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }
}
