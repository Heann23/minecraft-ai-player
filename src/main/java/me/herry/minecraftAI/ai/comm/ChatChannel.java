package me.herry.minecraftAI.ai.comm;

/**
 * 메시지를 실제 플랫폼으로 내보내는 어댑터. 인게임 채팅과 Discord 가 각각 하나씩 구현한다.
 * 들어오는 메시지는 어댑터가 IncomingMessage 로 바꿔서 CommunicationHub.receive() 에 넘긴다.
 * AI 의 판단 코드는 이 인터페이스 뒤에 무엇이 있는지 알지 못한다.
 */
public interface ChatChannel {
    MessageSource source();

    // 메인 스레드에서 호출된다. 네트워크 전송처럼 오래 걸리는 일은 구현체가 다른 스레드로 넘겨야 한다.
    void send(OutgoingMessage message);
}
