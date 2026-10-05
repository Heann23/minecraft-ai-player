package me.herry.minecraftAI.discord;

import java.util.Set;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

/** Personal actions always target the invoking Discord user; voice controls require fresh guild permission. */
public final class DiscordVoiceCommands {
    private static final Set<String> PERSONAL = Set.of("status", "forget", "me", "name", "speech", "joke", "chat", "game", "cancel");
    private static final Set<String> ADMIN = Set.of("leave", "resume", "quiet", "listen", "backup", "backups", "restore");
    private DiscordVoiceCommands() {}
    public static boolean allowed(String configuredGuild, String actualGuild, String action, boolean manageGuild) {
        return configuredGuild != null && configuredGuild.equals(actualGuild) && action != null
                && (PERSONAL.contains(action) || (ADMIN.contains(action) && manageGuild));
    }
    public static SlashCommandData definition() {
        return Commands.slash("herry", "해리 음성 대화와 내 기억을 관리해요").addSubcommands(
                new SubcommandData("status", "음성 대화 상태를 확인해요"),
                new SubcommandData("me", "내 이름·말투·장난 설정만 확인해요"),
                new SubcommandData("game", "설정한 게임 AI의 현재 상태만 확인해요"),
                new SubcommandData("chat", "해리와 나에게만 보이는 텍스트 대화를 해요")
                        .addOptions(new OptionData(OptionType.STRING, "message", "해리에게 할 말", true).setMinLength(1).setMaxLength(1000)),
                new SubcommandData("cancel", "내 텍스트 답변 준비를 중단해요"),
                new SubcommandData("forget", "해리가 기억한 내 정보를 지워요")
                        .addOptions(new OptionData(OptionType.STRING, "scope", "지울 범위, 생략하면 내 기억 전체", false)
                                .addChoice("내 기억 전체", "all").addChoice("이름·호칭만", "name")
                                .addChoice("말투 합의만", "speech").addChoice("장난 설정만", "joke")),
                new SubcommandData("name", "내가 불릴 이름을 직접 확정해요").addOptions(new OptionData(OptionType.STRING, "name", "저장할 내 호칭", true).setMinLength(1).setMaxLength(20)),
                new SubcommandData("speech", "해리가 나에게 쓸 말투를 직접 허락하거나 거절해요").addOption(OptionType.BOOLEAN, "allowed", "반말을 허락할까요?", true),
                new SubcommandData("joke", "나에게 가벼운 장난을 해도 되는지 직접 정해요").addOption(OptionType.BOOLEAN, "allowed", "가벼운 장난을 허용할까요?", true),
                new SubcommandData("leave", "관리자: 음성 채널을 나가고 자동 접속을 멈춰요"),
                new SubcommandData("resume", "관리자: 지정한 음성 채널에 다시 접속해요"),
                new SubcommandData("quiet", "관리자: 음성 수신과 답변을 중단해요"),
                new SubcommandData("listen", "관리자: 음성 대화를 다시 받아요"),
                new SubcommandData("backup", "관리자: 현재 확정 기억을 백업해요"),
                new SubcommandData("backups", "관리자: 최근 기억 백업의 식별자를 확인해요"),
                new SubcommandData("restore", "관리자: 대화를 멈추고 선택한 백업을 복원해요")
                        .addOptions(new OptionData(OptionType.STRING, "backup", "backups에서 확인한 백업 식별자", true).setMinLength(1).setMaxLength(72))
                        .addOption(OptionType.BOOLEAN, "confirm", "현재 기억을 선택한 백업으로 되돌릴까요?", true));
    }
}
