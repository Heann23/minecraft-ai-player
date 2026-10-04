package me.herry.minecraftAI.discord;

import java.util.ArrayList;
import java.util.List;

/** Exact text slices for incremental TTS. Joining the slices always reproduces the response. */
final class SentenceChunks {
    private SentenceChunks() {}
    static List<String> split(String text) {
        if (text == null || text.isBlank() || text.length() > 4000) throw new IllegalArgumentException("sentence text");
        List<String> chunks = new ArrayList<>(); int start = 0;
        while (start < text.length()) {
            int limit = Math.min(text.length(), start + 240), end = limit;
            for (int i = start; i < limit; i++) {
                char character = text.charAt(i);
                if (character == '\n' || (".!?。！？".indexOf(character) >= 0 && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))))) {
                    end = i + 1;
                    while (end < limit && Character.isWhitespace(text.charAt(end))) end++;
                    break;
                }
            }
            if (end == limit && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            chunks.add(text.substring(start, end)); start = end;
        }
        return List.copyOf(chunks);
    }
}
