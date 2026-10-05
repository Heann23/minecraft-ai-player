package me.herry.minecraftAI.discord;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile JDA jda;

    /** Must be called by the lifecycle worker, never on the server thread. */
    public static DiscordVoiceConnection open(Path directory, DiscordConfiguration configuration, String token,
                                               Consumer<String> diagnostic) throws Exception {
        DiscordVoiceConnection connection = new DiscordVoiceConnection(directory, configuration, diagnostic);
        try {
            connection.jda = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES)
                    .setMemberCachePolicy(MemberCachePolicy.VOICE).enableCache(CacheFlag.VOICE_STATE)
                    .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
                    .addEventListeners(connection).build();
            if (!connection.ready.await(30, TimeUnit.SECONDS) || connection.jda.getStatus() != JDA.Status.CONNECTED)
                throw new java.io.IOException("Discord gateway startup timeout or failure");
            connection.configure();
            return connection;
        } catch (Exception | LinkageError failed) { connection.close(); throw failed; }
    }
    private DiscordVoiceConnection(Path directory, DiscordConfiguration configuration, Consumer<String> diagnostic) throws Exception {
        this.configuration = configuration; this.diagnostic = diagnostic;
        dialogue = new OllamaDialogue(configuration.dialogue(), System::currentTimeMillis);
        LocalSpeechProviders createdSpeech = null; DiscordMemoryStore store = null;
        try {
            createdSpeech = new LocalSpeechProviders(configuration.speech());
            store = new DiscordMemoryStore(directory.resolve("discord"), configuration.discord().backup(), System::currentTimeMillis);
            session = new DiscordSession(configuration.discord(), store, createdSpeech, dialogue, createdSpeech, diagnostic,
                    System::currentTimeMillis, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), true);
            speech = createdSpeech; audio = new JdaAudioAdapter(session, configuration.minimumRms(), diagnostic);
        } catch (Exception | LinkageError failed) {
            if (store != null) store.close(); if (createdSpeech != null) createdSpeech.close(); dialogue.close(); throw failed;
        }
    }
    private void configure() {
        if (stopped.get()) return;
        var guild = jda.getGuildById(configuration.discord().guildId());
        if (guild == null) throw new IllegalStateException("Configured Discord guild unavailable");
        VoiceChannel channel = guild.getVoiceChannelById(configuration.discord().voiceChannelId());
        if (channel == null) throw new IllegalStateException("Configured normal voice channel unavailable");
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK))
            throw new IllegalStateException("Configured voice channel permissions missing");
        var manager = guild.getAudioManager(); manager.setSelfDeafened(false); manager.setSelfMuted(false); manager.setConnectTimeout(15_000);
        manager.setReceivingHandler(audio); manager.setSendingHandler(audio);
        manager.setConnectionListener(new ConnectionListener() {
            @Override public void onStatusChange(ConnectionStatus status) {
                if (stopped.get()) return;
                audio.connected(status == ConnectionStatus.CONNECTED);
                if (status == ConnectionStatus.CONNECTED) { participants(); diagnostic.accept("discord-voice-connected"); }
                else if (status.name().startsWith("ERROR_") || status.name().startsWith("DISCONNECTED_")) diagnostic.accept("discord-voice-disconnected");
            }
        });
        if (configuration.discord().autoConnect()) manager.openAudioConnection(channel);
        diagnostic.accept("discord-gateway-ready");
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
    @Override public void onReady(ReadyEvent event) { ready.countDown(); }
    @Override public void onShutdown(ShutdownEvent event) { ready.countDown(); audio.connected(false); }
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
            audio.connected(false); event.getGuild().getAudioManager().closeAudioConnection(); return;
        }
        String channel = configuration.discord().voiceChannelId();
        if ((event.getChannelJoined() != null && event.getChannelJoined().getId().equals(channel))
                || (event.getChannelLeft() != null && event.getChannelLeft().getId().equals(channel))) participants();
    }
    @Override public void stop() { stopped.set(true); audio.close(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stop();
        try { if (jda != null) jda.shutdownNow(); }
        finally { try { session.close(); } finally { try { speech.close(); } finally { dialogue.close(); } } }
    }
}
