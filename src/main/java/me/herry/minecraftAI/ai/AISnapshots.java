package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.build.BuildJob;
import me.herry.minecraftAI.ai.crafting.FurnaceJob;
import me.herry.minecraftAI.persist.AISnapshot;
import me.herry.minecraftAI.persist.BodyState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * AI 의 현재 상태를 저장용 스냅샷으로 만들고, 스냅샷에서 되살린다.
 * 무엇을 저장할지는 각 시스템이 스스로 정하고(exportState), 여기서는 그것들을 한데 모으기만 한다.
 */
final class AISnapshots {
    private static final String MEMORY = "memory";
    private static final String WORLD_MODEL = "worldModel";
    private static final String BODY = "body";
    private static final String BUILD = "build";
    private static final String FURNACE = "furnace";

    private AISnapshots() {
    }

    static AISnapshot capture(AIPlayer ai) {
        AISnapshot snapshot = new AISnapshot();
        snapshot.name = ai.getName();
        AISkin skin = ai.getSkin();
        if (skin != null) {
            snapshot.skinValue = skin.value();
            snapshot.skinSignature = skin.signature();
        }
        // 죽어서 리스폰을 기다리는 중이었다면, 되살아난 뒤에 하려던 대로(계속 움직일지 멈출지) 저장한다.
        snapshot.running = ai.runsAfterRestore();
        snapshot.ticks = ai.getTicks();

        Player player = ai.getPlayer();
        boolean alive = ai.getBody().isUsable();
        // 죽어 있는 동안 몸은 죽은 자리(용암 속, 낭떠러지 아래)에 남아 있다. 그 자리가 아니라 되살아날 자리를 저장한다.
        Location location = alive ? player.getLocation() : respawnPoint(player);
        snapshot.world = location.getWorld() == null ? null : location.getWorld().getUID();
        snapshot.x = location.getX();
        snapshot.y = location.getY();
        snapshot.z = location.getZ();
        snapshot.yaw = location.getYaw();
        snapshot.pitch = location.getPitch();

        snapshot.sections.put(MEMORY, ai.getMemory().exportState(ai.getTicks()));
        snapshot.sections.put(WORLD_MODEL, ai.getWorldModel().exportState());
        snapshot.sections.put(BODY, alive ? BodyState.capture(player) : BodyState.captureDead(player));
        BuildJob job = ai.getBuildJob();
        if (job != null) snapshot.sections.put(BUILD, job.exportState());
        // 화로에 넣어 둔 것은 서버를 재시작해도 화로에 남아 있으므로, 어느 화로였는지를 기억해 둔다.
        FurnaceJob furnace = ai.getFurnaceJob();
        if (furnace != null) snapshot.sections.put(FURNACE, furnace.exportState());
        return snapshot;
    }

    // 죽은 AI 가 되살아날 자리. 침대 같은 리스폰 지점이 없으면 기본 월드의 스폰 지점이다.
    private static Location respawnPoint(Player player) {
        Location respawn = player.getRespawnLocation();
        if (respawn != null && respawn.getWorld() != null) return respawn;
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? player.getLocation() : worlds.getFirst().getSpawnLocation();
    }

    static void restore(AIPlayer ai, AISnapshot snapshot, Logger logger) {
        ai.restoreTicks(snapshot.ticks);
        ai.getMemory().importState(snapshot.section(MEMORY));
        ai.getWorldModel().importState(snapshot.section(WORLD_MODEL));
        BodyState.apply(ai.getPlayer(), snapshot.section(BODY), logger);
        Map<String, Object> build = snapshot.section(BUILD);
        if (!build.isEmpty()) ai.setBuildJob(BuildJob.importState(build));
        Map<String, Object> furnace = snapshot.section(FURNACE);
        if (!furnace.isEmpty()) ai.setFurnaceJob(FurnaceJob.importState(furnace));
    }
}
