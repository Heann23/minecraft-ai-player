package me.herry.minecraftAI.ai.comm;

/**
 * 사람이 AI 에게(또는 AI 가 들을 수 있는 곳에서) 한 말. 어느 플랫폼에서 왔든 같은 모양이다.
 *
 * @param sender  말한 사람의 이름
 * @param direct  AI 에게 직접 한 말인지 (Discord 의 전용 채널이나 귓속말 등). false 면 본문에 AI 이름이 있을 때만 반응한다.
 * @param trusted AI 에게 일을 시킬 수 있는 사람인지. false 면 질문에만 대답하고 명령은 듣지 않는다.
 */
public record IncomingMessage(MessageSource source, String sender, String text, boolean direct, boolean trusted) {
    public static IncomingMessage inGame(String sender, String text, boolean trusted) {
        return new IncomingMessage(MessageSource.IN_GAME, sender, text, false, trusted);
    }

    public IncomingMessage withText(String newText) {
        return new IncomingMessage(source, sender, newText, direct, trusted);
    }
}
