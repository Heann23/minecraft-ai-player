package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 경로 위의 나무 문을 열고, 지나간 뒤에 닫는다. 문을 열어 둔 채로 두면 밤에 몬스터가 집 안으로 들어온다.
 * 이미 열려 있던 문이라도 지나갔으면 닫는다. 그래야 한번 닫지 못한 문이 계속 열린 채로 남지 않는다.
 */
final class DoorOpener {
    // 문에서 이만큼 멀어지면 지나간 것으로 보고 닫는다.
    private static final double CLOSE_DISTANCE_SQ = 1.7 * 1.7;
    private static final double OPEN_DISTANCE_SQ = 2.2 * 2.2;
    // 이보다 멀어진 문은 잊는다. 죽거나 순간이동한 뒤에 멀리 있는 문을 건드리지 않기 위해서다.
    private static final double FORGET_DISTANCE_SQ = 16.0 * 16.0;

    // 지나가는 중이거나 방금 지나온 문 (아래쪽 블록). 지나간 뒤에 닫는다.
    private @Nullable Block passing;

    /**
     * @param next 다음에 갈 칸. 그 칸에 문이 있으면 다가갔을 때 연다.
     */
    void tick(Player player, @Nullable BlockPoint next) {
        World world = player.getWorld();
        Location location = player.getLocation();
        closeBehind(player, location);
        if (next == null || !Positions.isLoaded(world, next)) return;

        Block door = lowerHalf(Positions.block(world, next));
        if (door == null || !(door.getBlockData() instanceof Door data)) return;
        if (Positions.center(world, next).distanceSquared(location) > OPEN_DISTANCE_SQ) return;

        if (!data.isOpen()) setOpen(player, door, data, true);
        passing = door;
    }

    /**
     * 이동이 끝났거나 중단됐을 때 호출한다. 지나온 문이 있으면 닫는다.
     * 문 칸에 서 있는 채로 멈췄으면 닫지 않고 기억해 두었다가, 다음에 움직여서 문을 벗어난 뒤에 닫는다.
     */
    void finish(Player player) {
        if (passing == null) return;
        Block door = passing;
        if (!isNearbyAndLoaded(door, player)) {
            passing = null;
            return;
        }
        if (isStandingIn(player.getLocation(), door)) return;
        if (door.getBlockData() instanceof Door data && data.isOpen()) setOpen(player, door, data, false);
        passing = null;
    }

    private void closeBehind(Player player, Location location) {
        if (passing == null) return;
        Block door = passing;
        if (!isNearbyAndLoaded(door, player) || !(door.getBlockData() instanceof Door data)) {
            passing = null;
            return;
        }
        double dx = door.getX() + 0.5 - location.getX();
        double dz = door.getZ() + 0.5 - location.getZ();
        if (dx * dx + dz * dz < CLOSE_DISTANCE_SQ) return;
        if (data.isOpen()) setOpen(player, door, data, false);
        passing = null;
    }

    // 같은 월드에 있고 청크가 로드되어 있으며 가까이 있는 문인지. 그렇지 않은 문의 블록은 읽지 않는다 (청크 동기 로드 방지).
    private static boolean isNearbyAndLoaded(Block door, Player player) {
        World world = door.getWorld();
        if (!world.equals(player.getWorld()) || !world.isChunkLoaded(door.getX() >> 4, door.getZ() >> 4)) return false;
        Location location = player.getLocation();
        double dx = door.getX() + 0.5 - location.getX();
        double dy = door.getY() - location.getY();
        double dz = door.getZ() + 0.5 - location.getZ();
        return dx * dx + dy * dy + dz * dz <= FORGET_DISTANCE_SQ;
    }

    private static boolean isStandingIn(Location location, Block door) {
        return location.getBlockX() == door.getX() && location.getBlockZ() == door.getZ()
                && Math.abs(location.getBlockY() - door.getY()) <= 1;
    }

    // 문의 위쪽 블록이면 아래쪽 블록을 돌려준다. 나무 문이 아니면 null.
    private static @Nullable Block lowerHalf(Block block) {
        if (!Tag.WOODEN_DOORS.isTagged(block.getType()) || !(block.getBlockData() instanceof Door data)) return null;
        return data.getHalf() == Bisected.Half.TOP ? block.getRelative(0, -1, 0) : block;
    }

    private static void setOpen(Player player, Block door, Door data, boolean open) {
        data.setOpen(open);
        // 물리 갱신을 켜면 위쪽 절반도 같은 상태로 맞춰진다.
        door.setBlockData(data, true);
        player.swingMainHand();
        door.getWorld().playSound(door.getLocation(), open ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE, 1.0F, 1.0F);
    }
}
