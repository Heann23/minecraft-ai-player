package me.herry.minecraftAI.ai.comm;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

/**
 * 인게임 채팅으로 내보내는 채널. 실제 플레이어가 채팅에 말한 것처럼 "<이름> 내용" 꼴로 모든 접속자에게 보인다.
 * 대화 모델이 늦게 만든 대답도 CommunicationHub.deliver() 를 거쳐 여기로 나온다.
 */
public final class InGameChatChannel implements ChatChannel {
    @Override
    public MessageSource source() {
        return MessageSource.IN_GAME;
    }

    @Override
    public void send(OutgoingMessage message) {
        Bukkit.getServer().sendMessage(Component.text("<" + message.speaker() + "> " + message.text()));
    }
}
