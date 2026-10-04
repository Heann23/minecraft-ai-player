package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 베기 시작한 나무 한 그루. 한번 베기 시작한 나무는 위쪽 원목까지 모두 벤다.
 * 손이 닿지 않는 높이의 원목은 나무 기둥 자리에 블록을 쌓고 올라가서 베고, 다 베면 쌓은 블록을 캐면서 내려온다.
 */
public final class TreeJob {
    private static final int MAX_LOGS = 96;
    private static final int MAX_RADIUS = 5;
    private static final int MAX_HEIGHT = 32;
    // 이 시간 동안 한 개도 베지 못하면 그 나무는 포기한다.
    private static final long STALE_TICKS = 6000L;
    private static final double ABANDON_DISTANCE = 48.0;
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final UUID world;
    // 처음 벤 원목의 자리. 나무 기둥이 있던 줄이며, 높은 곳은 이 줄에 블록을 쌓고 올라간다.
    private final BlockPoint base;
    private final Set<BlockPoint> logs = new HashSet<>();
    // 쌓고 올라간 블록. 아래쪽부터 순서대로 들어 있다.
    private final Deque<BlockPoint> pillar = new ArrayDeque<>();
    private long lastProgress;

    private TreeJob(UUID world, BlockPoint base, long now) {
        this.world = world;
        this.base = base;
        this.lastProgress = now;
    }

    /**
     * 방금 벤 원목에 이어진 나머지 원목을 찾아서 작업을 만든다.
     * 잎이 붙어 있지 않은 원목 덩어리(사람이 지은 통나무 집 등)이거나 남은 원목이 없으면 null.
     */
    public static @Nullable TreeJob start(World world, BlockPoint broken, long now) {
        TreeJob job = new TreeJob(world.getUID(), broken, now);
        Deque<BlockPoint> queue = new ArrayDeque<>();
        Set<BlockPoint> visited = new HashSet<>();
        queue.add(broken);
        visited.add(broken);
        while (!queue.isEmpty() && job.logs.size() < MAX_LOGS) {
            BlockPoint current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPoint next = current.offset(dx, dy, dz);
                        if (!visited.add(next) || !withinTree(broken, next) || !isLog(world, next)) continue;
                        job.logs.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        for (BlockPoint log : job.logs) {
            if (hasLeavesAround(world, log)) return job;
        }
        return null;
    }

    private static boolean withinTree(BlockPoint base, BlockPoint point) {
        return Math.abs(point.x() - base.x()) <= MAX_RADIUS && Math.abs(point.z() - base.z()) <= MAX_RADIUS
                && point.y() >= base.y() - 1 && point.y() <= base.y() + MAX_HEIGHT;
    }

    private static boolean isLog(World world, BlockPoint point) {
        if (point.y() < world.getMinHeight() || point.y() >= world.getMaxHeight() || !Positions.isLoaded(world, point)) return false;
        return Tag.LOGS.isTagged(Positions.block(world, point).getType());
    }

    private static boolean hasLeavesAround(World world, BlockPoint log) {
        for (int[] face : FACES) {
            BlockPoint next = log.offset(face[0], face[1], face[2]);
            if (next.y() < world.getMinHeight() || next.y() >= world.getMaxHeight() || !Positions.isLoaded(world, next)) continue;
            if (Tag.LEAVES.isTagged(Positions.block(world, next).getType())) return true;
        }
        return false;
    }

    // 이미 사라진 원목과 쌓은 블록을 목록에서 뺀다.
    public void refresh(World world) {
        logs.removeIf(log -> Positions.isLoaded(world, log) && !isLog(world, log));
        pillar.removeIf(cell -> Positions.isLoaded(world, cell) && !Positions.block(world, cell).getType().isSolid());
    }

    public void onLogBroken(BlockPoint log, long now) {
        if (logs.remove(log)) lastProgress = now;
    }

    // 손이 닿지 않는 가지처럼 벨 수 없는 원목을 포기한다.
    public void dropLog(BlockPoint log) {
        logs.remove(log);
    }

    public void abandonLogs() {
        logs.clear();
    }

    public void addPillar(BlockPoint cell) {
        pillar.addLast(cell);
    }

    public boolean hasPillar() {
        return !pillar.isEmpty();
    }

    // 지금 딛고 서 있는 블록이 쌓아 올린 블록이면 그 위치. 아니면 null.
    public @Nullable BlockPoint pillarUnder(BlockPoint feet) {
        BlockPoint below = feet.offset(0, -1, 0);
        return pillar.contains(below) ? below : null;
    }

    // 가장 낮은 원목부터 벤다. 같은 높이면 가까운 것부터.
    public @Nullable BlockPoint lowest(BlockPoint from) {
        BlockPoint best = null;
        for (BlockPoint log : logs) {
            if (best == null || log.y() < best.y() || log.y() == best.y() && log.distanceSq(from) < best.distanceSq(from)) best = log;
        }
        return best;
    }

    public boolean contains(BlockPoint log) {
        return logs.contains(log);
    }

    public boolean hasLogs() {
        return !logs.isEmpty();
    }

    public boolean isStale(long now, BlockPoint feet) {
        return now - lastProgress > STALE_TICKS || feet.distance(base) > ABANDON_DISTANCE;
    }

    public UUID world() {
        return world;
    }

    public BlockPoint base() {
        return base;
    }

    // 나무 기둥 줄에서 땅을 딛고 설 수 있는 칸. 기둥 아래쪽 원목은 이미 베었으므로 비어 있다.
    public BlockPoint trunkStand(World world) {
        BlockPoint stand = base;
        for (int i = 0; i < 6; i++) {
            BlockPoint below = stand.offset(0, -1, 0);
            if (below.y() < world.getMinHeight() || !Positions.isLoaded(world, below)) break;
            Material type = Positions.block(world, below).getType();
            if (type.isSolid() || Tag.LOGS.isTagged(type)) break;
            stand = below;
        }
        return stand;
    }
}
