package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 몸이 들어가 있거나 경로의 다음 칸을 막고 있는 거미줄을 베어 낸다.
 * 거미줄 안에서는 걷는 속도가 4분의 1로 줄고 점프도 거의 되지 않는다. 검이나 가위로는 0.4초면 베지만 맨손으로는 20초가 걸리므로,
 * 벨 도구가 없으면 베지 않고 천천히 걸어서 지나간다. 다만 거미줄 안에서 아예 나아가지 못하고 있으면 맨손으로라도 벤다.
 * 블록을 바로 없애지 않고, 실제 플레이어처럼 바라보고 팔을 휘두르며 도구에 맞는 시간만큼 걸려서 벤다.
 */
final class WebCutter {
    private static final int SWING_INTERVAL = 4;
    // 다음 칸의 거미줄은 이만큼 다가갔을 때 벤다.
    private static final double CUT_DISTANCE_SQ = 2.0 * 2.0;

    private @Nullable Block cutting;
    private float progress;
    private int ticks;

    /**
     * @param next     다음에 갈 칸. 그 칸의 발이나 머리 높이에 거미줄이 있으면 다가가서 벤다.
     * @param hindered 거미줄 안에서 나아가지 못하고 있는지. 이때는 도구가 없어도 벤다.
     * @return 거미줄을 베는 중이면 true. 그동안은 걷지 않는다.
     */
    boolean tick(Player player, AIBody body, InventorySystem inventory, @Nullable BlockPoint next, boolean hindered, Consumer<String> debug) {
        Block web = findWeb(player, next);
        if (web == null) {
            reset();
            return false;
        }
        int toolSlot = inventory.bestToolSlot(web);
        if (toolSlot < 0 && !hindered && !web.equals(cutting)) {
            reset();
            return false;
        }

        if (!web.equals(cutting)) {
            cutting = web;
            progress = 0.0F;
            ticks = 0;
            if (toolSlot >= 0) inventory.equipSlot(toolSlot);
            debug.accept("Cutting the cobweb at " + web.getX() + " " + web.getY() + " " + web.getZ()
                    + (toolSlot >= 0 ? "" : " by hand"));
        }

        body.inputMove(0.0F, 0.0F);
        body.inputSprint(false);
        body.inputJump(false);
        body.lookAt(web.getX() + 0.5, web.getY() + 0.5, web.getZ() + 0.5);
        progress += web.getBreakSpeed(player);
        if (ticks++ % SWING_INTERVAL == 0) player.swingMainHand();
        if (progress >= 1.0F) {
            player.breakBlock(web);
            reset();
        }
        return true;
    }

    void reset() {
        cutting = null;
        progress = 0.0F;
        ticks = 0;
    }

    // 몸이 거미줄 안에 있는지 (발 칸이나 머리 칸)
    static boolean isInWeb(Player player) {
        Location location = player.getLocation();
        return isWeb(player.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ())
                || isWeb(player.getWorld(), location.getBlockX(), location.getBlockY() + 1, location.getBlockZ());
    }

    // 벨 거미줄. 몸이 들어가 있는 칸이 먼저이고, 그다음이 가까이 온 다음 칸이다. 없으면 null.
    private static @Nullable Block findWeb(Player player, @Nullable BlockPoint next) {
        World world = player.getWorld();
        Location location = player.getLocation();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (isWeb(world, x, y, z)) return world.getBlockAt(x, y, z);
        if (isWeb(world, x, y + 1, z)) return world.getBlockAt(x, y + 1, z);
        if (next == null) return null;

        double dx = next.x() + 0.5 - location.getX();
        double dz = next.z() + 0.5 - location.getZ();
        if (dx * dx + dz * dz > CUT_DISTANCE_SQ || Math.abs(next.y() - y) > 1) return null;
        if (isWeb(world, next.x(), next.y(), next.z())) return world.getBlockAt(next.x(), next.y(), next.z());
        if (isWeb(world, next.x(), next.y() + 1, next.z())) return world.getBlockAt(next.x(), next.y() + 1, next.z());
        return null;
    }

    private static boolean isWeb(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return false;
        return world.getBlockAt(x, y, z).getType() == Material.COBWEB;
    }
}
