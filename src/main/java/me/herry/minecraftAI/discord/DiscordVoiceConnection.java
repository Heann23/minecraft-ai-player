package me.herry.minecraftAI.discord;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

/** One explicitly configured guild/channel. JDA callbacks never access Bukkit or game world state. */
public final class DiscordVoiceConnection extends ListenerAdapter implements DiscordRuntime.Connection {
    private final DiscordConfiguration configuration;
    private final Consumer<String> diagnostic;
    private final LocalSpeechProviders speech;
    private final OllamaDialogue dialogue;
    private final DiscordSession session;
    private final JdaAudioAdapter audio;
    private final DiscordGameState game;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final DiscordGatewayReadiness ready = new DiscordGatewayReadiness();
    private volatile JDA jda;
    private volatile boolean connectWanted;
    private final java.util.concurrent.atomic.AtomicLong connectAttempt = new java.util.concurrent.atomic.AtomicLong();

    /** Must be called by the lifecycle worker, never on the server thread. */
    public static DiscordVoiceConnection open(Path directory, DiscordConfiguration configuration, String token,
                                               Consumer<String> diagnostic) throws Exception {
        return open(directory, configuration, token, diagnostic, null);
    }
    public static DiscordVoiceConnection open(Path directory, DiscordConfiguration configuration, String token,
                                               Consumer<String> diagnostic, DiscordGameState game) throws Exception {
        DiscordVoiceConnection connection = new DiscordVoiceConnection(directory, configuration, diagnostic, game);
        try {
            connection.jda = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES)
                    .setMemberCachePolicy(MemberCachePolicy.VOICE).enableCache(CacheFlag.VOICE_STATE)
                    .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
                    .addEventListeners(connection).build();
            if (!connection.ready.await(30, TimeUnit.SECONDS))
                throw new DiscordStartupFailure(DiscordStartupFailure.Reason.GATEWAY_NOT_READY);
            connection.configure();
            return connection;
        } catch (Exception | LinkageError failed) { connection.close(); throw failed; }
    }
    private DiscordVoiceConnection(Path directory, DiscordConfiguration configuration, Consumer<String> diagnostic, DiscordGameState game) throws Exception {
        this.configuration = configuration; this.diagnostic = diagnostic; connectWanted = configuration.discord().autoConnect();
        this.game = game;
        dialogue = new OllamaDialogue(configuration.dialogue(), System::currentTimeMillis,
                game == null ? () -> new DiscordGameState.View(DiscordGameState.Code.NOT_CONFIGURED, null) : game::view);
        LocalSpeechProviders createdSpeech = null; DiscordMemoryStore store = null;
        try {
            createdSpeech = new LocalSpeechProviders(configuration.speech());
            store = new DiscordMemoryStore(directory.resolve("discord"), configuration.discord().backup(), System::currentTimeMillis);
            if (store.status().recovered()) diagnostic.accept("discord-memory-recovered-from-backup");
            var grounded = new GroundedGameDialogue(dialogue,
                    game == null ? () -> new DiscordGameState.View(DiscordGameState.Code.NOT_CONFIGURED, null) : game::view,
                    configuration.discord(), System::currentTimeMillis);
            session = new DiscordSession(configuration.discord(), store, createdSpeech, grounded, createdSpeech, diagnostic,
                    System::currentTimeMillis, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), true, configuration.capturePolicy(), configuration.greetOnJoin());
            speech = createdSpeech; audio = new JdaAudioAdapter(session, configuration.minimumRms(), diagnostic);
        } catch (Exception | LinkageError failed) {
            if (store != null) store.close(); if (createdSpeech != null) createdSpeech.close(); dialogue.close(); throw failed;
        }
    }
    private void configure() throws DiscordStartupFailure {
        if (stopped.get()) return;
        var guild = jda.getGuildById(configuration.discord().guildId());
        if (guild == null) throw new DiscordStartupFailure(DiscordStartupFailure.Reason.GUILD_UNAVAILABLE);
        VoiceChannel channel = guild.getVoiceChannelById(configuration.discord().voiceChannelId());
        if (channel == null) throw new DiscordStartupFailure(DiscordStartupFailure.Reason.VOICE_CHANNEL_UNAVAILABLE);
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK))
            throw new DiscordStartupFailure(DiscordStartupFailure.Reason.VOICE_PERMISSIONS_MISSING);
        var manager = guild.getAudioManager(); manager.setSelfDeafened(false); manager.setSelfMuted(false); manager.setConnectTimeout(15_000);
        manager.setReceivingHandler(audio); manager.setSendingHandler(audio);
        manager.setConnectionListener(new ConnectionListener() {
            @Override public void onStatusChange(ConnectionStatus status) {
                if (stopped.get()) return;
                if (!connectWanted && status == ConnectionStatus.CONNECTED) { manager.closeAudioConnection(); return; }
                if (status.name().startsWith("DISCONNECTED_") && !status.shouldReconnect()) {
                    connectWanted = false; connectAttempt.incrementAndGet(); manager.setAutoReconnect(false);
                }
                audio.connected(status == ConnectionStatus.CONNECTED);
                if (status == ConnectionStatus.CONNECTED) { participants(); diagnostic.accept("discord-voice-connected"); }
                else if (status.name().startsWith("ERROR_") || status.name().startsWith("DISCONNECTED_")) diagnostic.accept("discord-voice-disconnected");
            }
        });
        if (game != null) game.configure(configuration.targetAi());
        guild.upsertCommand(DiscordVoiceCommands.definition()).queue(registered -> {
            if (connectWanted) connectWithNotice().whenComplete((ignored, failed) -> { if (failed != null) diagnostic.accept("discord-connect-failed"); });
        }, failed -> diagnostic.accept("discord-command-registration-failed"));
        diagnostic.accept("discord-gateway-ready");
    }
    private java.util.concurrent.CompletableFuture<Void> connectWithNotice() {
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        long attempt = connectAttempt.incrementAndGet();
        if (stopped.get() || !connectWanted) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Voice connection stopped"));
        var guild = jda.getGuildById(configuration.discord().guildId());
        var channel = guild == null ? null : guild.getVoiceChannelById(configuration.discord().voiceChannelId());
        if (channel == null || !guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT,
                Permission.VOICE_SPEAK, Permission.MESSAGE_SEND)) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Voice channel permissions unavailable"));
        channel.sendMessage("해리가 이 채널의 음성을 인식해 한국어로 대화합니다. 원본 음성은 파일로 저장하지 않습니다. "
                + "관리자는 /herry quiet로 수신·답변을 중단하거나 /herry leave로 나가게 할 수 있어요. "
                + "내 이름·말투는 /herry name, /herry speech로 직접 정하고 /herry forget으로 내 기억을 지울 수 있어요.")
                .setAllowedMentions(Set.of()).timeout(5, TimeUnit.SECONDS).queue(notice -> {
                    if (stopped.get() || !connectWanted || connectAttempt.get() != attempt) { completion.cancel(false); return; }
                    try { guild.getAudioManager().setAutoReconnect(true); guild.getAudioManager().openAudioConnection(channel); completion.complete(null); }
                    catch (RuntimeException failed) { completion.completeExceptionally(failed); }
                }, completion::completeExceptionally);
        return completion;
    }
    private void participants() {
        if (stopped.get()) return;
        var guild = jda.getGuildById(configuration.discord().guildId());
        var channel = guild == null ? null : guild.getVoiceChannelById(configuration.discord().voiceChannelId());
        if (channel == null) { audio.connected(false); return; }
        Set<String> ids = new HashSet<>(); Map<String, String> names = new HashMap<>(); Set<String> ambiguous = new HashSet<>();
        for (var member : channel.getMembers()) {
            if (member.getUser().isBot()) continue;
            ids.add(member.getId());
            for (String name : java.util.List.of(member.getEffectiveName(), member.getUser().getName())) {
                if (name.isBlank() || name.length() > 100) continue;
                String previous = names.putIfAbsent(name, member.getId());
                if (previous != null && !previous.equals(member.getId())) ambiguous.add(name);
            }
        }
        ambiguous.forEach(names::remove);
        if (ids.size() > 8) {
            audio.participants(Set.of(), Map.of()).whenComplete((ignored, failed) -> { if (failed != null) diagnostic.accept("discord-participants-rejected"); });
            diagnostic.accept("discord-participant-capacity"); return;
        }
        audio.participants(ids, names).whenComplete((ignored, failure) -> { if (failure != null) diagnostic.accept("discord-participants-rejected"); });
    }
    @Override public void onReady(ReadyEvent event) { ready.ready(); }
    @Override public void onShutdown(ShutdownEvent event) { ready.shutdown(); audio.connected(false); }
    @Override public void onSessionDisconnect(SessionDisconnectEvent event) { audio.connected(false); }
    @Override public void onSessionResume(SessionResumeEvent event) { resume(); }
    @Override public void onSessionRecreate(SessionRecreateEvent event) { resume(); }
    private void resume() {
        if (stopped.get() || jda == null) return;
        var guild = jda.getGuildById(configuration.discord().guildId());
        if (guild != null && guild.getAudioManager().isConnected()) { audio.connected(true); participants(); }
    }
    @Override public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (stopped.get() || !event.getGuild().getId().equals(configuration.discord().guildId())) return;
        var self = event.getGuild().getSelfMember();
        if (event.getMember().equals(self) && (event.getChannelJoined() == null
                || !event.getChannelJoined().getId().equals(configuration.discord().voiceChannelId()))) {
            connectWanted = false; connectAttempt.incrementAndGet();
            audio.connected(false); event.getGuild().getAudioManager().setAutoReconnect(false);
            event.getGuild().getAudioManager().closeAudioConnection(); return;
        }
        String channel = configuration.discord().voiceChannelId();
        if ((event.getChannelJoined() != null && event.getChannelJoined().getId().equals(channel))
                || (event.getChannelLeft() != null && event.getChannelLeft().getId().equals(channel))) participants();
    }
    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("herry") || event.getUser().isBot()) return;
        if (stopped.get() || event.getGuild() == null || !event.getGuild().getId().equals(configuration.discord().guildId())) {
            event.reply("이 서버에서는 해리 대화를 관리할 수 없어요.").setEphemeral(true).queue(ignored -> {}, failed -> diagnostic.accept("discord-command-reply-failed")); return;
        }
        event.deferReply(true).queue(hook -> event.getGuild().retrieveMemberById(event.getUser().getId()).useCache(false).timeout(5, TimeUnit.SECONDS).queue(member -> {
            if (stopped.get() || !DiscordVoiceCommands.allowed(configuration.discord().guildId(), event.getGuild().getId(), event.getSubcommandName(), member.hasPermission(Permission.MANAGE_SERVER))) {
                hook.editOriginal("채널 제어에는 서버 관리 권한이 필요해요. 내 기억은 본인만 수정할 수 있어요.").queue(ignored -> {}, failed -> diagnostic.accept("discord-command-reply-failed")); return;
            }
            java.util.concurrent.CompletableFuture<String> result;
            if (event.getSubcommandName().equals("me")) {
                session.personalSettings(event.getUser().getId()).whenComplete((reply, failed) -> {
                    if (failed != null || stopped.get() || !session.personalCurrent(reply)) {
                        hook.editOriginal("내 설정을 확인하지 못했거나 설정이 바뀌었어요. 다시 확인해 주세요.")
                                .queue(ignored -> {}, failure -> diagnostic.accept("discord-command-reply-failed")); return;
                    }
                    hook.editOriginal(reply.text()).setAllowedMentions(Set.of())
                            .setCheck(() -> !stopped.get() && session.personalCurrent(reply))
                            .queue(ignored -> {}, failure -> diagnostic.accept("discord-command-reply-failed"));
                }); return;
            }
            if (event.getSubcommandName().equals("chat")) {
                session.textReply(event.getUser().getId(), java.util.Objects.requireNonNull(event.getOption("message")).getAsString(), event.getId())
                        .whenComplete((reply, failed) -> {
                            if (failed != null || stopped.get() || !session.textCurrent(reply)) {
                                hook.editOriginal("대화를 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.").queue(ignored -> {}, failure -> diagnostic.accept("discord-command-reply-failed"));
                                return;
                            }
                            hook.editOriginal(reply.text()).setAllowedMentions(Set.of())
                                    .setCheck(() -> !stopped.get() && session.textCurrent(reply)).queue(sent -> session.textSubmitted(reply), failure -> {
                                session.textDiscard(reply); diagnostic.accept("discord-command-reply-failed");
                            });
                        });
                return;
            }
            try { result = command(event); }
            catch (RuntimeException invalid) { result = java.util.concurrent.CompletableFuture.completedFuture("입력값을 확인해 주세요. 호칭은 한글·영문 1~20자로 입력해요."); }
            result.whenComplete((message, failed) -> hook.editOriginal(failed == null ? message : "처리하지 못했어요. 연결이나 기억 저장 상태를 확인해 주세요.")
                    .queue(ignored -> {}, replyFailure -> diagnostic.accept("discord-command-reply-failed")));
        }, failed -> hook.editOriginal("현재 서버 참가자와 권한을 확인하지 못했어요.").queue(ignored -> {}, replyFailure -> diagnostic.accept("discord-command-reply-failed"))),
                failed -> diagnostic.accept("discord-command-ack-failed"));
    }
    private java.util.concurrent.CompletableFuture<String> command(SlashCommandInteractionEvent event) {
        String user = event.getUser().getId(), source = event.getId();
        return switch (event.getSubcommandName()) {
            case "game" -> java.util.concurrent.CompletableFuture.completedFuture(game == null ? "게임 상태 연결을 사용할 수 없어요." : game.describe());
            case "status" -> {
                var status = session.status();
                yield java.util.concurrent.CompletableFuture.completedFuture("음성 연결: " + (event.getGuild().getAudioManager().isConnected() ? "연결됨" : "연결 안 됨")
                        + " · 참가자: " + status.users() + "명 · 기억 저장: " + (status.memoryFailure() ? "확인 필요" : "정상")
                        + " · 기억 버전: " + status.memoryRevision()
                        + (status.memoryRecovered() ? " · 백업에서 자동 복구됨: " + java.time.Instant.ofEpochMilli(status.memoryRecoveredAt()) : "")
                        + (status.rejectedRecoveryPoints() > 0 ? " · 제외한 손상/미래 백업: " + status.rejectedRecoveryPoints() + "개" : "")
                        + "\n" + session.textStatus().describe() + "\n완료는 답변 준비 완료이며 Discord 전달 확인은 별도예요.");
            }
            case "forget" -> session.forget(user).thenApply(ignored -> "내 기억과 대화 문맥을 지웠어요. 삭제된 정보는 과거 백업에서도 다시 불러오지 않아요.");
            case "name" -> session.confirmedName(user, java.util.Objects.requireNonNull(event.getOption("name")).getAsString(), source).thenApply(ignored -> "내 호칭을 저장했어요.");
            case "speech" -> session.confirmedSpeechStyle(user, java.util.Objects.requireNonNull(event.getOption("allowed")).getAsBoolean(), source).thenApply(ignored -> "나에게 쓸 말투의 허락·거절을 저장했어요.");
            case "joke" -> {
                boolean allowed = java.util.Objects.requireNonNull(event.getOption("allowed")).getAsBoolean();
                yield session.confirmedJokes(user, allowed, source).thenApply(ignored -> allowed
                        ? "가벼운 장난을 허용하는 설정을 저장했어요. 진지한 대화에서는 장난을 줄일게요."
                        : "장난 없이 담백하게 이야기하는 설정을 저장했어요. /herry joke allowed:true로 바꿀 수 있어요.");
            }
            case "backup" -> session.backup().thenApply(id -> id == null ? "마지막 백업 이후 기억이 바뀌지 않았어요." : "확정 기억을 백업했어요: " + id);
            case "backups" -> session.backupIds().thenApply(ids -> ids.isEmpty() ? "아직 기억 백업이 없어요." : "최근 백업 식별자:\n" + String.join("\n", ids));
            case "restore" -> {
                if (!java.util.Objects.requireNonNull(event.getOption("confirm")).getAsBoolean())
                    yield java.util.concurrent.CompletableFuture.completedFuture("복원하지 않았어요. 복원은 음성 대화를 멈추고 현재 기억을 선택한 백업으로 되돌려요. 삭제·정정 기록은 유지해요.");
                yield audio.restoreBackup(java.util.Objects.requireNonNull(event.getOption("backup")).getAsString())
                        .thenApply(ignored -> "기억을 복원했어요. 삭제·정정 기록과 이전 대화의 폐기를 적용했어요. /herry listen으로 음성 대화를 다시 받아요.");
            }
            case "quiet" -> audio.listening(false).thenApply(ignored -> "음성 수신과 답변을 중단했어요. /herry listen으로 다시 받아요.");
            case "listen" -> audio.listening(true).thenApply(ignored -> "음성 대화를 다시 받아요. 연결이 끊겼다면 /herry resume을 사용해 주세요.");
            case "leave" -> {
                connectWanted = false; connectAttempt.incrementAndGet(); audio.connected(false);
                event.getGuild().getAudioManager().setAutoReconnect(false); event.getGuild().getAudioManager().closeAudioConnection();
                yield java.util.concurrent.CompletableFuture.completedFuture("음성 채널에서 나갔어요. /herry resume 전까지 자동으로 들어가지 않아요.");
            }
            case "resume" -> { connectWanted = true; yield connectWithNotice().thenApply(ignored -> "설정된 음성 채널에 접속을 요청했어요."); }
            default -> java.util.concurrent.CompletableFuture.completedFuture("지원하지 않는 명령이에요.");
        };
    }
    @Override public void stop() { stopped.set(true); if (game != null) game.configure(""); audio.close(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stop();
        try {
            if (jda != null) {
                jda.shutdownNow();
                try { if (!jda.awaitShutdown(2, TimeUnit.SECONDS)) diagnostic.accept("discord-gateway-shutdown-deadline"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); diagnostic.accept("discord-gateway-shutdown-interrupted"); }
            }
        }
        finally { try { session.close(); } finally { try { speech.close(); } finally { dialogue.close(); } } }
    }
}
