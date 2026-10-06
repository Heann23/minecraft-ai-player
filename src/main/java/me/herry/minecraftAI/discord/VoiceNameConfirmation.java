package me.herry.minecraftAI.discord;

/** Code-owned sentences for confirming a spoken name. The heard name is repeated back before anything is stored. */
final class VoiceNameConfirmation {
    private VoiceNameConfirmation() {}
    static String question(String name) {
        String polite = name.endsWith("님") || name.endsWith("씨") ? name : name + "님";
        return polite + (polite.endsWith("씨") ? "라고" : "이라고") + " 부르면 될까요?";
    }
    static String declined() { return "알겠어요. 호칭은 이름을 다시 말씀해 주시면 확인할게요."; }
}
