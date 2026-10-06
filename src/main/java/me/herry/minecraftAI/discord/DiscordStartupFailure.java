package me.herry.minecraftAI.discord;

import java.io.IOException;

/** Only code-owned diagnostic values, never external exception messages or credentials. */
final class DiscordStartupFailure extends IOException {
    enum Reason {
        GATEWAY_NOT_READY("discord-gateway-not-ready"),
        GUILD_UNAVAILABLE("discord-guild-unavailable"),
        VOICE_CHANNEL_UNAVAILABLE("discord-voice-channel-unavailable"),
        VOICE_PERMISSIONS_MISSING("discord-voice-permissions-missing"),
        // discord.yml problems: the section is named, the value and file text never are.
        CONFIG_SYNTAX("discord-config-syntax"),
        CONFIG_SIZE("discord-config-size"),
        CONFIG_UNREADABLE("discord-config-unreadable"),
        CONFIG_CONNECTION("discord-config-invalid-connection"),
        CONFIG_CONVERSATION("discord-config-invalid-conversation"),
        CONFIG_BACKUP("discord-config-invalid-backup"),
        CONFIG_AUDIO("discord-config-invalid-audio"),
        CONFIG_GAME("discord-config-invalid-game"),
        CONFIG_LLM("discord-config-invalid-llm"),
        CONFIG_SPEECH("discord-config-invalid-speech");
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
