package me.herry.minecraftAI.ai.comm;

import org.jetbrains.annotations.Nullable;

/**
 * AI 가 하는 말. 특정 플랫폼의 형식(색, 멘션 등)을 담지 않으며, 어떻게 보여 줄지는 각 채널이 정한다.
 *
 * @param urgent  급한 말인지. 급하지 않은 혼잣말은 너무 자주 하지 않도록 걸러진다.
 * @param replyTo 사람의 말에 대한 대답이면 그 말. 혼잣말이면 null.
 */
public record OutgoingMessage(String speaker, String text, boolean urgent, @Nullable IncomingMessage replyTo) {
    public static OutgoingMessage announcement(String speaker, String text, boolean urgent) {
        return new OutgoingMessage(speaker, text, urgent, null);
    }

    public static OutgoingMessage reply(String speaker, String text, IncomingMessage to) {
        return new OutgoingMessage(speaker, text, true, to);
    }
}
