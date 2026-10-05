package me.herry.minecraftAI.discord;

import java.io.IOException;

/** Only code-owned diagnostic values, never external exception messages or credentials. */
final class DiscordStartupFailure extends IOException {
    enum Reason {
        GATEWAY_NOT_READY("discord-gateway-not-ready"),
        GUILD_UNAVAILABLE("discord-guild-unavailable"),
        VOICE_CHANNEL_UNAVAILABLE("discord-voice-channel-unavailable"),
        VOICE_PERMISSIONS_MISSING("discord-voice-permissions-missing");
        final String code;
        Reason(String code) { this.code = code; }
    }
    private final Reason reason;
    DiscordStartupFailure(Reason reason) {
        super(java.util.Objects.requireNonNull(reason).code);
        this.reason = reason;
    }
    String diagnostic() { return reason.code; }
}
