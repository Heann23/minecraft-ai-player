package me.herry.minecraftAI.ai.comm;

/**
 * 메시지가 들어온 곳. 대답을 같은 곳으로 돌려보내는 데 쓴다.
 */
public enum MessageSource {
    IN_GAME,
    DISCORD,
    // 플러그인 자신이나 관리 명령이 보낸 것
    SYSTEM
}
