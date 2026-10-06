package me.herry.minecraftAI.discord;

import me.herry.minecraftAI.ai.comm.ChatChannel;
import me.herry.minecraftAI.ai.comm.IncomingMessage;
import me.herry.minecraftAI.ai.comm.MessageSource;
import me.herry.minecraftAI.ai.comm.OutgoingMessage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** One main-thread adapter. It collects scoped replies and performs no Discord/network calls. */
public final class DiscordChatChannel implements ChatChannel {
    private final IdentityHashMap<IncomingMessage, List<String>> scopes = new IdentityHashMap<>();
    @Override public MessageSource source() { return MessageSource.DISCORD; }
    @Override public void send(OutgoingMessage message) {
        if (message.replyTo() == null || message.replyTo().source() != MessageSource.DISCORD) return;
        List<String> lines = scopes.get(message.replyTo());
        if (lines == null || lines.size() >= 16 || message.text() == null) return;
        int remaining = 4000 - lines.stream().mapToInt(String::length).sum();
        if (remaining > 0) lines.add(message.text().substring(0, Math.min(remaining, message.text().length())));
    }
    public List<String> collect(IncomingMessage original, Runnable delivery) {
        if (original.source() != MessageSource.DISCORD || scopes.containsKey(original)) throw new IllegalArgumentException("reply scope");
        List<String> lines = new ArrayList<>(); scopes.put(original, lines);
        try { delivery.run(); return List.copyOf(lines); }
        finally { scopes.remove(original); }
    }
}
