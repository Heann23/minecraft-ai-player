package me.herry.minecraftAI.ai.perception;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryEntry;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 멀리 있는 나무를 알아본다. 사람은 평원에 서면 멀리 보이는 나뭇잎을 보고 그쪽으로 걸어가지,
 * 아무 방향으로나 돌아다니다 나무와 마주치기를 기다리지 않는다.
 *
 * 주변 블록 검색(BlockScanner)은 16칸 안의 원목만 찾으므로, 여기서는 지표면의 가장 높은 블록만 듬성듬성 살펴서
 * 그것이 나뭇잎인 곳을 찾는다. 로드되어 있는 청크만 보고, 한 번에 천 칸 남짓만 읽는다.
 */
public final class TreeSpotter {
    // 가까운 곳은 촘촘히, 먼 곳은 듬성듬성 본다. 스폰 지점에서 나무까지 60칸쯤 떨어진 곳에서는
    // 56칸까지만 보면 나무를 못 보고 엉뚱한 쪽으로 몇 분씩 헤매기도 했다.
    private static final int RANGE = 96;
    private static final int NEAR_RANGE = 56;
    private static final int STEP = 4;
    private static final int FAR_STEP = 8;
    // 이보다 가까운 곳은 주변 블록 검색이 이미 살핀 범위다. 거기에 원목이 없었다면 나무가 아니라 덤불이다.
    private static final int MIN_DISTANCE = 14;
    // 이미 가 본 곳에서 이 거리 안에 있는 나뭇잎은 다시 가 보지 않는다.
    private static final double CHECKED_RADIUS = 12.0;
    // 큰 나무 꼭대기를 목표로 잡으면 닿을 수 없으므로, 목표 높이는 지금 높이에서 이만큼까지만 올려 잡는다.
    private static final int MAX_TARGET_RISE = 4;

    private TreeSpotter() {
    }

    /**
     * 가장 가까이 보이는 나뭇잎 아래의 땅. 보이는 것이 없으면 null.
     */
    public static @Nullable BlockPoint spot(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return null;
        BlockPoint feet = ai.getPosition();
        // 이미 가 본 곳과, 가려 했지만 길이 없었던 곳 근처의 나뭇잎은 다시 고르지 않는다.
        List<MemoryEntry> checked = new ArrayList<>(ai.getMemory().all(MemoryType.CHECKED_PLACE, world.getUID(), ai.getTicks()));
        checked.addAll(ai.getMemory().all(MemoryType.UNREACHABLE, world.getUID(), ai.getTicks()));

        BlockPoint best = null;
        int bestDistanceSq = Integer.MAX_VALUE;
        for (int dx = -RANGE; dx <= RANGE; dx += STEP) {
            for (int dz = -RANGE; dz <= RANGE; dz += STEP) {
                int distanceSq = dx * dx + dz * dz;
                if (distanceSq < MIN_DISTANCE * MIN_DISTANCE || distanceSq >= bestDistanceSq) continue;
                boolean far = Math.abs(dx) > NEAR_RANGE || Math.abs(dz) > NEAR_RANGE;
                if (far && (dx % FAR_STEP != 0 || dz % FAR_STEP != 0)) continue;
                int x = feet.x() + dx;
                int z = feet.z() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                Material top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING).getType();
                if (!Tag.LEAVES.isTagged(top) && !Tag.LOGS.isTagged(top)) continue;

                int ground = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                BlockPoint target = new BlockPoint(x, Math.min(ground, feet.y() + MAX_TARGET_RISE), z);
                if (isChecked(checked, target)) continue;
                best = target;
                bestDistanceSq = distanceSq;
            }
        }
        return best;
    }

    private static boolean isChecked(List<MemoryEntry> checked, BlockPoint target) {
        for (MemoryEntry entry : checked) {
            double dx = entry.pos().x() - target.x();
            double dz = entry.pos().z() - target.z();
            if (dx * dx + dz * dz <= CHECKED_RADIUS * CHECKED_RADIUS) return true;
        }
        return false;
    }
}
