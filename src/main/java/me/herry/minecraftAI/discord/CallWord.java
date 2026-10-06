package me.herry.minecraftAI.discord;

import java.util.regex.Pattern;

/**
 * How people call Herry at the start of a sentence. Speech recognition often writes the call as its own sentence
 * ("해리야. 지금 뭐 해?"), so punctuation after the name separates as well as a space does. One rule for every reader.
 */
final class CallWord {
    static final String SUFFIX = "(?:야|아|님|씨)?";
    static final String SEPARATOR = "[\\s,，:!?~.。…]";
    private static final Pattern CALL = Pattern.compile("(?iu)^(?:해리|Herry)" + SUFFIX + "(?:" + SEPARATOR + "+|$)");
    private CallWord() {}
    /** True only when the sentence starts with the call. A name inside a longer word ("해리포터") or later in the sentence is not one. */
    static boolean called(String text) { return text != null && CALL.matcher(text.strip()).find(); }
    /** The sentence after its leading call, or the stripped sentence itself when it does not start with one. */
    static String body(String text) {
        String stripped = text.strip(); var matcher = CALL.matcher(stripped);
        return matcher.find() ? stripped.substring(matcher.end()) : stripped;
    }
}
