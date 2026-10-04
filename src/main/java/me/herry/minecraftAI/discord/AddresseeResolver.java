package me.herry.minecraftAI.discord;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative first-pass routing. LLM recipient suggestions remain untrusted candidates. */
public final class AddresseeResolver {
    private AddresseeResolver() {}
    public record Result(ConversationTurns.Address address, String otherUserId) {}
    public static Result resolve(String text, String speaker, Set<String> participants, Map<String, String> names) {
        if (text == null || text.length() > 2000 || !participants.contains(speaker)) return unknown();
        String body = text.strip();
        // Quoted names and reported speech do not constitute an explicit call.
        if (body.startsWith("\"") || body.startsWith("'") || body.startsWith("“") || body.startsWith("‘")) return unknown();
        if (called(body, "해리", true) || called(body, "Herry", true)) return character();
        String matched = null;
        for (Map.Entry<String, String> entry : names.entrySet()) {
            if (participants.contains(entry.getValue()) && !speaker.equals(entry.getValue()) && called(body, entry.getKey(), false)) {
                if (matched != null && !matched.equals(entry.getValue())) return unknown();
                matched = entry.getValue();
            }
        }
        return matched == null ? unknown() : new Result(ConversationTurns.Address.OTHER_USER, matched);
    }
    /** A model's style-based guess cannot become an explicit call or change permission. */
    public static Result validateSuggestion(String suggestedUser, Set<String> participants) {
        if (suggestedUser == null || !participants.contains(suggestedUser)) return unknown();
        return new Result(ConversationTurns.Address.OTHER_USER, suggestedUser);
    }
    private static boolean called(String text, String name, boolean character) {
        if (name == null || name.isBlank() || name.length() > 100) return false;
        String suffix = character ? "(?:야|님|씨)?" : "(?:야|아|님|씨)?";
        return Pattern.compile("(?iu)^" + Pattern.quote(name) + suffix + "(?:[\\s,!?~:]+|$)").matcher(text).find();
    }
    private static Result character() { return new Result(ConversationTurns.Address.CHARACTER, ""); }
    private static Result unknown() { return new Result(ConversationTurns.Address.UNKNOWN, ""); }
}
