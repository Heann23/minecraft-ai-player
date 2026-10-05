package me.herry.minecraftAI.ai.team;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 들이 파 놓은 굴(계단과 수평 굴)의 경로를 기억한다.
 * 땅속으로 내려갈 때 새 굴을 파지 않고 이미 있는 굴을 따라가게 해서, 동료끼리 같은 길로 다니고 도구도 아낀다.
 * 좌표는 판 순서대로 이어져 있으므로 앞쪽이 입구, 뒤쪽이 가장 깊은 끝이다. Bukkit 에 의존하지 않는다.
 */
public final class ShaftRegistry {
    /**
     * 굴 하나. points 는 입구부터 끝까지 걸어갈 수 있는 순서로 이어진 발 위치다.
     */
    public record Shaft(String owner, UUID world, List<BlockPoint> points) {
        public BlockPoint entrance() {
            return points.getFirst();
        }

        public BlockPoint end() {
            return points.getLast();
        }

        // from 에 가장 가까운 지점의 순서 번호
        public int nearestIndex(BlockPoint from) {
            int best = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                double distance = points.get(i).distanceSq(from);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            return best;
        }
    }

    // 이 거리(제곱) 안에서 이어서 판 칸은 같은 굴로 본다. 계단 한 단은 대각선으로 한 칸 아래라 제곱 거리 2 다.
    private static final double JOIN_DISTANCE_SQ = 6.0;
    private static final int MAX_POINTS = 512;
    private static final int MAX_SHAFTS_PER_OWNER = 4;
    // 굴의 끝에서 더 팔 수 없는 일이 이만큼의 간격을 두고 이 횟수만큼 되풀이되면 막다른 굴로 본다.
    // 한 번은 공중에 떠 있었거나 지나가는 몬스터 때문일 수 있다.
    public static final long BLOCKED_RECHECK_TICKS = 100L;
    private static final int DEAD_END_STRIKES = 2;
    private static final int MAX_BLOCKED_ENDS = 32;

    private record EndKey(UUID world, BlockPoint end) {
    }

    private record Blocked(int strikes, long lastTick) {
    }

    private static final class Builder {
        final UUID world;
        final List<BlockPoint> points = new ArrayList<>();

        Builder(UUID world) {
            this.world = world;
        }
    }

    private final Map<String, Deque<Builder>> byOwner = new HashMap<>();
    private final Map<EndKey, Blocked> blockedEnds = new LinkedHashMap<>();

    /**
     * 굴을 한 칸 더 판 뒤에 새 발 위치를 기록한다. 마지막 칸과 이어지지 않으면 새 굴로 기록한다.
     */
    public void record(String owner, UUID world, BlockPoint point) {
        Deque<Builder> shafts = byOwner.computeIfAbsent(owner, key -> new ArrayDeque<>());
        Builder last = shafts.peekLast();
        if (last != null && last.world.equals(world) && !last.points.isEmpty()) {
            BlockPoint tail = last.points.getLast();
            if (tail.equals(point)) return;
            if (tail.distanceSq(point) <= JOIN_DISTANCE_SQ) {
                last.points.add(point);
                if (last.points.size() > MAX_POINTS) last.points.removeFirst();
                return;
            }
        }
        Builder shaft = new Builder(world);
        shaft.points.add(point);
        shafts.addLast(shaft);
        if (shafts.size() > MAX_SHAFTS_PER_OWNER) shafts.removeFirst();
    }

    /**
     * from 에서 걸어 들어갈 수 있을 만큼 가까운 굴 중에서, 끝이 from 보다 minDepth 이상 깊은 굴.
     * 여럿이면 가장 깊이 내려가는 굴을 고른다. 없으면 null.
     *
     * @param accessRange 굴의 어느 한 지점이라도 이 거리 안에 있어야 한다
     */
    public @Nullable Shaft deeper(UUID world, BlockPoint from, double accessRange, int minDepth) {
        Shaft best = null;
        for (Shaft shaft : all(world)) {
            if (shaft.end().y() > from.y() - minDepth || isDeadEnd(shaft)) continue;
            if (shaft.points().get(shaft.nearestIndex(from)).distance(from) > accessRange) continue;
            if (best == null || shaft.end().y() < best.end().y()) best = shaft;
        }
        return best;
    }

    /**
     * from 근처를 지나가고 입구가 from 보다 minRise 이상 높은 굴. 땅속에서 지상으로 올라갈 때 쓴다. 없으면 null.
     */
    public @Nullable Shaft higher(UUID world, BlockPoint from, double accessRange, int minRise) {
        Shaft best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Shaft shaft : all(world)) {
            int index = shaft.nearestIndex(from);
            double distance = shaft.points().get(index).distance(from);
            if (distance > accessRange) continue;
            if (shaft.entrance().y() < from.y() + minRise) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = shaft;
            }
        }
        return best;
    }

    // pos 가 어떤 굴 안(굴의 한 지점에서 range 안)에 있는지
    public boolean passesNear(UUID world, BlockPoint pos, double range) {
        double rangeSq = range * range;
        for (Deque<Builder> shafts : byOwner.values()) {
            for (Builder builder : shafts) {
                if (!builder.world.equals(world)) continue;
                for (BlockPoint point : builder.points) {
                    if (point.distanceSq(pos) <= rangeSq) return true;
                }
            }
        }
        return false;
    }

    // pos 가 어떤 굴에서 발을 디디는 칸인지. 그 바로 아래 블록은 굴의 발판이라 캐면 굴이 끊긴다.
    public boolean isStep(UUID world, BlockPoint pos) {
        for (Deque<Builder> shafts : byOwner.values()) {
            for (Builder builder : shafts) {
                if (builder.world.equals(world) && builder.points.contains(pos)) return true;
            }
        }
        return false;
    }

    // 어떤 굴의 끝(가장 깊이 판 곳)이 pos 에서 range 안에 있는지
    public boolean endsNear(UUID world, BlockPoint pos, double range) {
        for (Deque<Builder> shafts : byOwner.values()) {
            for (Builder builder : shafts) {
                if (builder.world.equals(world) && !builder.points.isEmpty() && builder.points.getLast().distance(pos) <= range) return true;
            }
        }
        return false;
    }

    /**
     * pos 가까이에서 끝나는 굴의 끝에서 더 팔 수 없었다고 적어 둔다. 간격을 두고 되풀이되면 그 굴은 막다른 굴이 된다.
     * 막다른 굴은 "이미 파 둔 굴"로 따라 내려가지 않는다. 적어 두지 않으면 다른 데로 옮겨 갔다가도 그 끝으로 되돌아온다.
     * 굴을 더 파서 끝이 달라지면 다시 쓸 수 있는 굴이 된다.
     */
    public void noteBlockedEnd(UUID world, BlockPoint pos, double range, long now) {
        for (Shaft shaft : all(world)) {
            if (shaft.end().distance(pos) > range) continue;
            EndKey key = new EndKey(world, shaft.end());
            Blocked before = blockedEnds.get(key);
            if (before == null) blockedEnds.put(key, new Blocked(1, now));
            else if (now - before.lastTick() >= BLOCKED_RECHECK_TICKS) blockedEnds.put(key, new Blocked(before.strikes() + 1, now));
        }
        while (blockedEnds.size() > MAX_BLOCKED_ENDS) blockedEnds.remove(blockedEnds.keySet().iterator().next());
    }

    public boolean isDeadEnd(Shaft shaft) {
        Blocked blocked = blockedEnds.get(new EndKey(shaft.world(), shaft.end()));
        return blocked != null && blocked.strikes() >= DEAD_END_STRIKES;
    }

    // pos 가까이에서 끝나는 막다른 굴. 없으면 null.
    public @Nullable Shaft deadEndNear(UUID world, BlockPoint pos, double range) {
        for (Shaft shaft : all(world)) {
            if (shaft.end().distance(pos) <= range && isDeadEnd(shaft)) return shaft;
        }
        return null;
    }

    // pos 가 막다른 굴에서 발을 디디는 칸인지. 그 굴을 따라 걷는 것은 새로 파는 것이 아니다.
    public boolean isDeadEndStep(UUID world, BlockPoint pos) {
        for (Shaft shaft : all(world)) {
            if (isDeadEnd(shaft) && shaft.points().contains(pos)) return true;
        }
        return false;
    }

    // 이 AI 가 판 굴 중 가장 최근의 것. 없으면 null.
    public @Nullable Shaft latestOf(String owner, UUID world) {
        Deque<Builder> shafts = byOwner.get(owner);
        if (shafts == null) return null;
        var iterator = shafts.descendingIterator();
        while (iterator.hasNext()) {
            Builder builder = iterator.next();
            if (builder.world.equals(world) && !builder.points.isEmpty()) return snapshot(owner, builder);
        }
        return null;
    }

    /**
     * 막혀서 더 이상 다닐 수 없는 굴을 지운다. 같은 굴을 계속 따라가려다 실패하지 않게 한다.
     */
    public void discard(Shaft shaft) {
        Deque<Builder> shafts = byOwner.get(shaft.owner());
        if (shafts == null) return;
        shafts.removeIf(builder -> builder.world.equals(shaft.world()) && !builder.points.isEmpty()
                && builder.points.getFirst().equals(shaft.entrance()));
    }

    private List<Shaft> all(UUID world) {
        List<Shaft> result = new ArrayList<>();
        for (Map.Entry<String, Deque<Builder>> entry : byOwner.entrySet()) {
            for (Builder builder : entry.getValue()) {
                if (builder.world.equals(world) && builder.points.size() >= 2) result.add(snapshot(entry.getKey(), builder));
            }
        }
        return result;
    }

    private static Shaft snapshot(String owner, Builder builder) {
        return new Shaft(owner, builder.world, List.copyOf(builder.points));
    }
}
