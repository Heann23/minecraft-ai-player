package me.herry.minecraftAI.discord;

import java.util.regex.Pattern;

/** Exact interruption forms, never model inference or substring matching. */
public final class ConversationStop {
    private static final Pattern STOP = Pattern.compile(
            "(?iu)^(?:(?:해리(?:야|님|씨)?|Herry(?:야|님|씨)?)[\\s,!?~:]+)?(?:그만|잠깐|멈춰|그만해|잠시만)[.!?。！？]*$");
    private ConversationStop() {}
    public static boolean requested(String text) {
        return text != null && text.length() <= 100 && STOP.matcher(text.strip()).matches();
    }
}
