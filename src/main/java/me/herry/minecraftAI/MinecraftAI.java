package me.herry.minecraftAI;

import me.herry.minecraftAI.ai.AIController;
import me.herry.minecraftAI.ai.AIDebugger;
import me.herry.minecraftAI.commands.AICommand;
import me.herry.minecraftAI.config.AIConfig;
import me.herry.minecraftAI.events.AIPlayerListener;
import me.herry.minecraftAI.persist.AIStateStore;
import me.herry.minecraftAI.persist.YamlStateStore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

public final class MinecraftAI extends JavaPlugin {
    private AIController controller;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        AIConfig config = new AIConfig(getConfig(), getLogger()::warning);
        AIDebugger debugger = new AIDebugger(getLogger(), config.debugEnabled);

        AIStateStore store = config.persistenceEnabled ? new YamlStateStore(new File(getDataFolder(), "players"), getLogger()) : null;
        controller = new AIController(this, config, debugger, store);

        this.getServer().getPluginManager().registerEvents(new AIPlayerListener(controller, debugger), this);
        Objects.requireNonNull(this.getCommand("ai")).setExecutor(new AICommand(controller, debugger, config));

        // 플레이어를 접속시키는 일은 서버가 완전히 켜진 뒤(첫 틱)에 한다.
        Bukkit.getScheduler().runTask(this, () -> {
            int restored = controller.restoreAll();
            if (restored > 0) getLogger().info("Restored " + restored + " AI player(s) from saved state.");
        });
    }

    // 서버가 꺼지거나 플러그인이 내려갈 때 AI 의 상태를 저장하고, AI 플레이어와 틱 작업이 남지 않게 정리한다.
    @Override
    public void onDisable() {
        if (controller != null) controller.shutdown();
        controller = null;
    }
}
