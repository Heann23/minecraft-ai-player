package me.herry.minecraftAI;

import me.herry.minecraftAI.ai.AIController;
import me.herry.minecraftAI.ai.AIDebugger;
import me.herry.minecraftAI.ai.learned.ManagedPolicyBackend;
import me.herry.minecraftAI.ai.comm.CommunicationHub;
import me.herry.minecraftAI.ai.comm.InGameChatChannel;
import me.herry.minecraftAI.commands.AICommand;
import me.herry.minecraftAI.config.AIConfig;
import me.herry.minecraftAI.events.AIPlayerListener;
import me.herry.minecraftAI.persist.AIStateStore;
import me.herry.minecraftAI.persist.YamlStateStore;
import me.herry.minecraftAI.discord.DiscordConfiguration;
import me.herry.minecraftAI.discord.DiscordRuntime;
import me.herry.minecraftAI.discord.DiscordVoiceConnection;
import me.herry.minecraftAI.discord.DiscordGameState;
import me.herry.minecraftAI.ai.comm.GameStateReader;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

public final class MinecraftAI extends JavaPlugin {
    private AIController controller;
    private DiscordRuntime discord;
    private DiscordGameState discordGame;
    private org.bukkit.scheduler.BukkitTask discordGameTask;
    private ManagedPolicyBackend brainBackend;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        AIConfig config = new AIConfig(getConfig(), getLogger()::warning);
        AIDebugger debugger = new AIDebugger(getLogger(), config.debugEnabled);

        // AI 와 사람 사이의 말은 모두 이 허브를 지난다. Discord 를 붙일 때는 채널 어댑터를 하나 더 등록하면 된다.
        CommunicationHub hub = new CommunicationHub(config.chatEnabled, config.chatMinInterval, Bukkit::getCurrentTick);
        hub.addChannel(new InGameChatChannel());

        AIStateStore store = config.persistenceEnabled ? new YamlStateStore(new File(getDataFolder(), "players"), getLogger()) : null;
        if (config.managedBackend) {
            var root = getDataFolder().toPath().toAbsolutePath().normalize();
            brainBackend = new ManagedPolicyBackend(new ManagedPolicyBackend.Settings(config.backendPython,
                    root.resolve(config.backendScript), root.resolve(config.backendCatalog), root.resolve(config.backendRuns),
                    root.resolve(config.trainingChannel), config.backendModel, config.allowPolicyTraining,
                    config.backendMaxTransitions, config.backendSessionSaveSeconds));
        }
        controller = new AIController(this, config, debugger, hub, store, brainBackend == null ? null : brainBackend.channel());

        this.getServer().getPluginManager().registerEvents(new AIPlayerListener(controller, debugger), this);
        Objects.requireNonNull(this.getCommand("ai")).setExecutor(new AICommand(controller, debugger, config));

        // 별도 설정·네트워크·음성·기억 I/O는 서버 틱 밖에서 시작하고 실패해도 게임 AI는 계속한다.
        java.nio.file.Path discordDirectory = getDataFolder().toPath();
        java.util.logging.Logger discordLogger = getLogger();
        java.util.function.Consumer<String> discordDiagnostic = code -> discordLogger.info("Discord: " + code);
        var gameReader = new GameStateReader(controller, Bukkit::isPrimaryThread);
        var gameState = new DiscordGameState(Bukkit::isPrimaryThread, gameReader::read,
                () -> java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), discordDiagnostic);
        discordGame = gameState;
        discordGameTask = Bukkit.getScheduler().runTaskTimer(this, gameState::refresh, 20, 20);
        discord = new DiscordRuntime(() -> DiscordConfiguration.load(discordDirectory, () -> getResource("discord.yml")),
                System::getenv, (settings, token) -> DiscordVoiceConnection.open(discordDirectory, settings, token, discordDiagnostic, gameState), discordDiagnostic);

        // 플레이어를 접속시키는 일은 서버가 완전히 켜진 뒤(첫 틱)에 한다.
        if (brainBackend == null) Bukkit.getScheduler().runTask(this, this::restoreSavedPlayers);
        else {
            var backend = brainBackend;
            backend.start().whenComplete((brain, failure) -> {
                if (!isEnabled()) return;
                Bukkit.getScheduler().runTask(this, () -> {
                    if (controller == null || backend.isClosed()) return;
                    if (failure != null) {
                        controller.setBackendAvailable(false);
                        getLogger().log(java.util.logging.Level.WARNING,
                                "Managed brain unavailable; saved players retained, no new model or Teacher fallback", failure);
                        return;
                    }
                    controller.setBackendAvailable(true);
                    getLogger().info("Managed brain ready: " + brain);
                    restoreSavedPlayers();
                    backend.exitCode().whenComplete((code, exitFailure) -> {
                        if (!isEnabled() || backend.isClosed()) return;
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (controller == null || backend.isClosed()) return;
                            controller.setBackendAvailable(false);
                            getLogger().warning("Managed brain exited; AI stopped without changing its model. Exit=" + code);
                            backend.close();
                        });
                    });
                });
            });
        }
    }

    private void restoreSavedPlayers() {
        if (controller == null) return;
        int restored = controller.restoreAll();
        if (restored > 0) getLogger().info("Restored " + restored + " AI player(s) from saved state.");
    }

    // 서버가 꺼지거나 플러그인이 내려갈 때 AI 의 상태를 저장하고, AI 플레이어와 틱 작업이 남지 않게 정리한다.
    @Override
    public void onDisable() {
        if (discordGameTask != null) discordGameTask.cancel();
        discordGameTask = null;
        if (discordGame != null) discordGame.close();
        discordGame = null;
        if (discord != null) {
            discord.close();
            discord.awaitServerShutdown(Bukkit.isStopping(), java.time.Duration.ofSeconds(6));
        }
        discord = null;
        if (controller != null) controller.shutdown();
        controller = null;
        if (brainBackend != null) brainBackend.close();
        brainBackend = null;
    }
}
