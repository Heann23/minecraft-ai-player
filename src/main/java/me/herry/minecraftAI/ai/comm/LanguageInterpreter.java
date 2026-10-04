package me.herry.minecraftAI.ai.comm;

/**
 * 사람의 말을 의도(Intent)로 바꾼다. 기본 구현은 규칙 기반(KeywordInterpreter)이고,
 * 나중에 LLM 을 쓰는 구현으로 바꿔 끼울 수 있다. 어느 쪽이든 AI 의 게임 플레이는 이 해석기 없이도 동작한다.
 *
 * 구현은 빠르게 끝나야 한다 (메인 스레드에서 호출된다). 네트워크를 쓰는 해석기는 어댑터 쪽에서 미리 해석한 뒤
 * 결과만 넘기는 식으로 만들어야 한다.
 */
public interface LanguageInterpreter {
    Intent interpret(IncomingMessage message);
}
