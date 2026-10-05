package me.herry.minecraftAI.discord;

/** The current user's last addressed utterance, never another user's input or an unheard model draft. */
final class DialogueContext {
    private DialogueContext() {}
    static String currentInput(ResponsePipeline.Request request) {
        for (int index = request.context().size() - 1; index >= 0; index--) {
            var line = request.context().get(index);
            if (!line.assistant() && line.speaker().equals(request.turn().userId()) && line.target().equals("Herry")) return line.text();
        }
        return "";
    }
}
