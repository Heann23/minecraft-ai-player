package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryEntry;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.BlockScanner;
import me.herry.minecraftAI.ai.team.ShaftRegistry;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * 기억 속에서 지금 캐러 갈 만한 블록을 고른다.
 * 기억은 오래됐을 수 있으므로 실제 블록이 아직 그 자리에 있는지 확인하고, 사라졌으면 기억에서 지운다.
 */
public final class ResourceLocator {
    // 발보다 이만큼 높이 있는 블록부터는 손이 닿기 어려우므로 그만큼 먼 것으로 친다.
    private static final int EASY_HEIGHT = 2;
    private static final double HEIGHT_PENALTY = 3.0;
    private static final double LOW_FIRST_WEIGHT = 1.5;
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private ResourceLocator() {
    }

    public static @Nullable BlockPoint locate(AIPlayer ai, MemoryType type) {
        return locate(ai, type, Double.MAX_VALUE);
    }

    // maxDistance 보다 멀리 있는 블록은 후보에서 뺀다.
    public static @Nullable BlockPoint locate(AIPlayer ai, MemoryType type, double maxDistance) {
        return locate(ai, type, maxDistance, false);
    }

    // 걸어갈 길이 없다고 기억해 둔 블록 중에서 고른다. 굴을 파서 다가갈 대상을 찾을 때 쓴다.
    public static @Nullable BlockPoint locateBlocked(AIPlayer ai, MemoryType type, double maxDistance) {
        return locate(ai, type, maxDistance, true);
    }

    private static @Nullable BlockPoint locate(AIPlayer ai, MemoryType type, double maxDistance, boolean blockedOnly) {
        MemorySystem memory = ai.getMemory();
        World world = ai.getPlayer().getWorld();
        UUID worldId = world.getUID();
        BlockPoint from = ai.getPosition();
        long now = ai.getTicks();

        Base home = ai.getWorldModel().homeIn(worldId);
        ShaftRegistry shafts = ai.getTeam().getShafts();
        BlockPoint best = null;
        double bestScore = Double.MAX_VALUE;
        for (MemoryEntry entry : memory.all(type, worldId, now)) {
            BlockPoint pos = entry.pos();
            if (pos.distance(from) > maxDistance) continue;
            // 집의 벽과 바닥으로 쓴 돌은 캐러 갈 자원이 아니다.
            if (isResource(type) && home != null && home.isInsideBuilding(worldId, pos)) continue;
            if (!canGatherAt(type, pos, from, step -> shafts.isStep(worldId, step))) continue;
            // 청크가 내려가 있으면 확인할 수 없으니 이번에는 후보에서만 뺀다.
            if (!Positions.isLoaded(world, pos)) continue;
            if (BlockScanner.interestOf(Positions.block(world, pos).getType()) != type) {
                memory.forget(type, worldId, pos);
                continue;
            }
            // 누군가 이미 열어 본 상자는 더 볼 필요가 없다.
            if (type == MemoryType.LOOT_CHEST && !BlockScanner.isUnopenedLootChest(Positions.block(world, pos))) {
                memory.forget(type, worldId, pos);
                continue;
            }
            if (memory.contains(MemoryType.UNREACHABLE, worldId, pos, now) != blockedOnly) continue;
            // 다른 AI 가 캐러 가는 나무나 돌은 건드리지 않는다.
            if (isResource(type) && ai.getTeam().isClaimedByOther(ai, worldId, pos)) continue;

            // 같은 나무 기둥이라면 아래쪽부터 캔다. 위쪽을 먼저 캐면 떨어진 아이템이 남은 기둥 위에 얹혀서 줍기 어렵다.
            double score = pos.distance(from) + Math.max(0, pos.y() - from.y()) * LOW_FIRST_WEIGHT
                    + Math.max(0, pos.y() - from.y() - EASY_HEIGHT) * HEIGHT_PENALTY;
            if (score >= bestScore) continue;
            // 물에 닿아 있는 돌이나 광석은 고르지 않는다. 캐려면 물속에 서야 해서 캐는 속도가 5배 느려지고,
            // 캐고 나면 물이 굴로 흘러들어 떠 있는 채로 아무것도 못 하게 된다. 다이아몬드도 마찬가지다.
            if (floods(type) && touchesWater(world, pos)) continue;
            bestScore = score;
            best = pos;
        }
        return best;
    }

    private static boolean floods(MemoryType type) {
        return type == MemoryType.STONE || type == MemoryType.COAL_ORE || type == MemoryType.IRON_ORE || type == MemoryType.DIAMOND_ORE;
    }

    // 광석 발판도 보호하되, 발판이 아닌 발 아래 광석까지 포기하지는 않는다.
    static boolean canGatherAt(MemoryType type, BlockPoint target, BlockPoint feet, Predicate<BlockPoint> isStep) {
        if (isResource(type) && DigRules.cutsShaft(target, isStep)) return false;
        return type != MemoryType.STONE || DigRules.keepsWayOut(target, feet, isStep);
    }

    private static boolean touchesWater(World world, BlockPoint pos) {
        for (BlockFace face : FACES) {
            int x = pos.x() + face.getModX();
            int y = pos.y() + face.getModY();
            int z = pos.z() + face.getModZ();
            // 옆 청크가 내려가 있으면 읽지 않는다 (동기 청크 로드 방지).
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (world.getBlockAt(x, y, z).getType() == Material.WATER) return true;
        }
        return false;
    }

    private static boolean isResource(MemoryType type) {
        return type == MemoryType.TREE || type == MemoryType.STONE || type == MemoryType.COAL_ORE || type == MemoryType.IRON_ORE
                || type == MemoryType.DIAMOND_ORE || type == MemoryType.GRAVEL;
    }

    public static boolean knows(AIPlayer ai, MemoryType type) {
        return locate(ai, type) != null;
    }
}
