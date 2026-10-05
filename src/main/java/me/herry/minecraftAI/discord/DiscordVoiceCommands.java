package me.herry.minecraftAI.discord;

import java.util.Set;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

/** Personal actions always target the invoking Discord user; voice controls require fresh guild permission. */
public final class DiscordVoiceCommands {
    private static final Set<String> PERSONAL = Set.of("status", "forget", "name", "speech");
    private static final Set<String> ADMIN = Set.of("leave", "resume", "quiet", "listen");
    private DiscordVoiceCommands() {}
    public static boolean allowed(String configuredGuild, String actualGuild, String action, boolean manageGuild) {
        return configuredGuild != null && configuredGuild.equals(actualGuild) && action != null
                && (PERSONAL.contains(action) || (ADMIN.contains(action) && manageGuild));
    }
    public static SlashCommandData definition() {
        return Commands.slash("herry", "해리 음성 대화와 내 기억을 관리해요").addSubcommands(
                new SubcommandData("status", "음성 대화 상태를 확인해요"),
                new SubcommandData("forget", "해리가 기억한 내 정보를 지워요"),
                new SubcommandData("name", "내가 불릴 이름을 직접 확정해요").addOptions(new OptionData(OptionType.STRING, "name", "저장할 내 호칭", true).setMinLength(1).setMaxLength(20)),
                new SubcommandData("speech", "해리가 나에게 쓸 말투를 직접 허락하거나 거절해요").addOption(OptionType.BOOLEAN, "allowed", "반말을 허락할까요?", true),
                new SubcommandData("leave", "관리자: 음성 채널을 나가고 자동 접속을 멈춰요"),
                new SubcommandData("resume", "관리자: 지정한 음성 채널에 다시 접속해요"),
                new SubcommandData("quiet", "관리자: 음성 수신과 답변을 중단해요"),
                new SubcommandData("listen", "관리자: 음성 대화를 다시 받아요"));
    }
}
