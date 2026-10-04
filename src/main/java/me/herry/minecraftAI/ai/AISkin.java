package me.herry.minecraftAI.ai;

/**
 * AI 플레이어에게 입힐 스킨. 모장 서버가 서명한 스킨 정보(textures 속성)를 그대로 담는다.
 * 서명이 없는 스킨은 클라이언트가 표시하지 않으므로 값과 서명이 항상 함께 있어야 한다.
 */
public record AISkin(String value, String signature) {
}
