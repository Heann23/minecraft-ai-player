package me.herry.minecraftAI.discord;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only check of discord.yml against the configuration the connection is running with. Nothing is applied: a changed file takes
 * effect only after a restart. Reports section names and fixed codes, never values, ids, paths or file text.
 */
final class DiscordConfigurationDiff {
    @FunctionalInterface interface Loader { DiscordConfiguration load() throws Exception; }
    static final String SAME = "discord.yml은 지금 실행 중인 설정과 같아요.";
    private DiscordConfigurationDiff() {}

    static List<String> changedSections(DiscordConfiguration running, DiscordConfiguration file) {
        var changed = new ArrayList<String>();
        DiscordSettings a = running.discord(), b = file.discord();
        if (a.enabled() != b.enabled() || !a.tokenEnvironment().equals(b.tokenEnvironment()) || !a.guildId().equals(b.guildId())
                || !a.voiceChannelId().equals(b.voiceChannelId()) || !a.characterId().equals(b.characterId()) || a.autoConnect() != b.autoConnect())
            changed.add("connection");
        if (a.followupMillis() != b.followupMillis() || a.contextLines() != b.contextLines() || running.greetOnJoin() != file.greetOnJoin())
            changed.add("conversation");
        if (!a.backup().equals(b.backup())) changed.add("backup");
        if (Double.compare(running.minimumRms(), file.minimumRms()) != 0 || running.endSilenceMillis() != file.endSilenceMillis()) changed.add("audio");
        if (!running.targetAi().equals(file.targetAi())) changed.add("game");
        if (!running.dialogue().equals(file.dialogue())) changed.add("llm");
        if (!running.speech().equals(file.speech())) changed.add("speech");
        return List.copyOf(changed);
    }
    /** One of three fixed answers. The running configuration is never replaced. */
    static String check(DiscordConfiguration running, Loader loader) {
        DiscordConfiguration file;
        try { file = loader.load(); }
        catch (DiscordStartupFailure known) { return invalid(known.diagnostic()); }
        catch (DiscordConfiguration.Invalid invalid) { return invalid(invalid.diagnostic()); }
        catch (Exception other) { return invalid("discord-start-failed"); }
        var changed = changedSections(running, file);
        return changed.isEmpty() ? SAME : "discord.yml은 유효하지만 실행 중인 설정과 달라요. 바뀐 부분: " + String.join(", ", changed)
                + ". 적용하려면 서버를 재시작해야 하고, 지금은 실행 중인 설정 그대로예요.";
    }
    private static String invalid(String code) {
        return "discord.yml에 문제가 있어요: " + code + ". 고치지 않고 재시작하면 Discord가 시작하지 않아요. 지금은 실행 중인 설정 그대로예요.";
    }
}
