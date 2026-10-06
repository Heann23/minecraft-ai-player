package me.herry.minecraftAI.ai.memory;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * AI 가 알게 된 정보를 저장한다. Bukkit 에 의존하지 않으며, 시간은 AI 틱으로 센다.
 */
public final class MemorySystem {
    public record FailureRecord(String action, String reason, long tick) {
    }

    public record AttackRecord(UUID world, BlockPoint pos, UUID attacker, long tick) {
    }

    public record Visit(UUID world, BlockPoint pos) {
    }

    // 종류별로 이보다 많이 쌓이면 가장 오래된 기억부터 버린다.
    private static final int MAX_ENTRIES_PER_TYPE = 64;
    private static final int MAX_FAILURES = 20;
    private static final int MAX_RECENT_PATH = 32;
    private static final long UNREACHABLE_BASE_TTL = 1200L;
    private static final int UNREACHABLE_MAX_DOUBLINGS = 4;
    private static final int MAX_STRIKE_ENTRIES = 128;

    private final Map<MemoryType, List<MemoryEntry>> entries = new EnumMap<>(MemoryType.class);
    // 같은 곳에 가지 못한 횟수. 넣은 순서를 유지해서 한도를 넘으면 가장 오래된 것부터 버린다.
    private final Map<Visit, Integer> unreachableStrikes = new LinkedHashMap<>();
    private final Deque<FailureRecord> failures = new ArrayDeque<>();
    private final Deque<Visit> recentPath = new ArrayDeque<>();
    private AttackRecord lastAttack;
    private String lastGoal = "";

    public void remember(MemoryType type, UUID world, BlockPoint pos, long now, long ttlTicks) {
        List<MemoryEntry> list = entries.computeIfAbsent(type, key -> new ArrayList<>());
        list.removeIf(entry -> entry.world().equals(world) && entry.pos().equals(pos));
        long expires = ttlTicks < 0 ? MemoryEntry.PERMANENT : now + ttlTicks;
        list.add(new MemoryEntry(type, world, pos, now, expires));
        if (list.size() > MAX_ENTRIES_PER_TYPE) list.removeFirst();
    }

    public void rememberPermanent(MemoryType type, UUID world, BlockPoint pos, long now) {
        remember(type, world, pos, now, MemoryEntry.PERMANENT);
    }

    public void forget(MemoryType type, UUID world, BlockPoint pos) {
        List<MemoryEntry> list = entries.get(type);
        if (list != null) list.removeIf(entry -> entry.world().equals(world) && entry.pos().equals(pos));
    }

    public boolean contains(MemoryType type, UUID world, BlockPoint pos, long now) {
        List<MemoryEntry> list = entries.get(type);
        if (list == null) return false;
        for (MemoryEntry entry : list) {
            if (!entry.isExpired(now) && entry.world().equals(world) && entry.pos().equals(pos)) return true;
        }
        return false;
    }

    /**
     * 같은 월드에서 from 에 가장 가까운 기억을 찾는다.
     */
    public Optional<MemoryEntry> nearest(MemoryType type, UUID world, BlockPoint from, long now, Predicate<MemoryEntry> filter) {
        List<MemoryEntry> list = entries.get(type);
        if (list == null) return Optional.empty();

        MemoryEntry best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MemoryEntry entry : list) {
            if (entry.isExpired(now) || !entry.world().equals(world) || !filter.test(entry)) continue;
            double distance = entry.pos().distanceSq(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry;
            }
        }
        return Optional.ofNullable(best);
    }

    public Optional<MemoryEntry> nearest(MemoryType type, UUID world, BlockPoint from, long now) {
        return nearest(type, world, from, now, entry -> true);
    }

    public List<MemoryEntry> all(MemoryType type, UUID world, long now) {
        List<MemoryEntry> result = new ArrayList<>();
        List<MemoryEntry> list = entries.get(type);
        if (list == null) return result;
        for (MemoryEntry entry : list) {
            if (!entry.isExpired(now) && entry.world().equals(world)) result.add(entry);
        }
        return result;
    }

    public int count(MemoryType type, UUID world, long now) {
        return all(type, world, now).size();
    }

    public void prune(long now) {
        for (List<MemoryEntry> list : entries.values()) {
            list.removeIf(entry -> entry.isExpired(now));
        }
    }

    public void recordFailure(String action, String reason, long now) {
        failures.addLast(new FailureRecord(action, reason, now));
        if (failures.size() > MAX_FAILURES) failures.removeFirst();
    }

    // 최근 window 틱 동안 해당 행동이 실패한 횟수.
    public int countRecentFailures(String action, long now, long window) {
        int count = 0;
        for (FailureRecord failure : failures) {
            if (failure.action().equals(action) && now - failure.tick() <= window) count++;
        }
        return count;
    }

    public List<FailureRecord> getFailures() {
        return List.copyOf(failures);
    }

    public void recordAttack(UUID world, BlockPoint pos, UUID attacker, long now) {
        lastAttack = new AttackRecord(world, pos, attacker, now);
    }

    public Optional<AttackRecord> getLastAttack() {
        return Optional.ofNullable(lastAttack);
    }

    public boolean wasAttackedWithin(long now, long ticks) {
        return lastAttack != null && now - lastAttack.tick() <= ticks;
    }

    // 같은 칸에 머무는 동안에는 중복으로 쌓지 않는다.
    public void recordVisit(UUID world, BlockPoint pos) {
        Visit last = recentPath.peekLast();
        if (last != null && last.world().equals(world) && last.pos().equals(pos)) return;
        recentPath.addLast(new Visit(world, pos));
        if (recentPath.size() > MAX_RECENT_PATH) recentPath.removeFirst();
    }

    public List<Visit> getRecentPath() {
        return List.copyOf(recentPath);
    }

    /**
     * 가 보려 했지만 길이 없었던 곳을 기억한다. 같은 곳에서 거듭 실패할수록 더 오래 피한다
     * (1분, 2분, 4분 ... 최대 16분). 잠깐 막혔던 곳은 곧 다시 가 보고, 정말 갈 수 없는 곳에는 매달리지 않게 된다.
     *
     * @return 이번에 기억해 둔 시간(틱)
     */
    public long rememberUnreachable(UUID world, BlockPoint pos, long now) {
        Visit key = new Visit(world, pos);
        int strikes = unreachableStrikes.merge(key, 1, Integer::sum);
        if (unreachableStrikes.size() > MAX_STRIKE_ENTRIES) unreachableStrikes.remove(unreachableStrikes.keySet().iterator().next());
        long ttl = UNREACHABLE_BASE_TTL << Math.min(strikes - 1, UNREACHABLE_MAX_DOUBLINGS);
        remember(MemoryType.UNREACHABLE, world, pos, now, ttl);
        return ttl;
    }

    public void rememberMiningObstructed(UUID world, BlockPoint pos, long now) {
        long ttl = rememberUnreachable(world, pos, now);
        remember(MemoryType.MINING_OBSTRUCTED, world, pos, now, ttl);
    }

    // 그곳에 실제로 도착했으면 "갈 수 없는 곳" 이력을 지운다.
    public void clearUnreachable(UUID world, BlockPoint pos) {
        unreachableStrikes.remove(new Visit(world, pos));
        forget(MemoryType.UNREACHABLE, world, pos);
    }

    /**
     * 서버를 재시작해도 남겨 둘 기억을 저장 파일에 쓸 수 있는 단순한 값으로 바꾼다.
     * 만료된 것과 저장하지 않는 종류(나무, 돌, 잠깐 피하는 곳)는 뺀다.
     */
    public Map<String, Object> exportState(long now) {
        List<String> lines = new ArrayList<>();
        for (List<MemoryEntry> list : entries.values()) {
            for (MemoryEntry entry : list) {
                if (!entry.type().isPersistent() || entry.isExpired(now)) continue;
                lines.add(entry.type().name() + "|" + entry.world() + "|" + entry.pos().encode() + "|" + entry.createdTick() + "|" + entry.expiresTick());
            }
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("entries", lines);
        state.put("lastGoal", lastGoal);
        return state;
    }

    // 읽을 수 없는 줄(없어진 종류, 망가진 좌표)은 건너뛴다.
    public void importState(Map<String, Object> state) {
        if (state.get("entries") instanceof List<?> lines) {
            for (Object line : lines) {
                String[] parts = String.valueOf(line).split("\\|");
                if (parts.length != 5) continue;
                try {
                    MemoryType type = MemoryType.valueOf(parts[0]);
                    MemoryEntry entry = new MemoryEntry(type, UUID.fromString(parts[1]), BlockPoint.parse(parts[2]),
                            Long.parseLong(parts[3]), Long.parseLong(parts[4]));
                    List<MemoryEntry> list = entries.computeIfAbsent(type, key -> new ArrayList<>());
                    list.removeIf(old -> old.world().equals(entry.world()) && old.pos().equals(entry.pos()));
                    list.add(entry);
                    if (list.size() > MAX_ENTRIES_PER_TYPE) list.removeFirst();
                } catch (IllegalArgumentException ignored) {
                    // 저장 뒤에 없어진 종류이거나 좌표가 망가진 줄
                }
            }
        }
        if (state.get("lastGoal") != null) lastGoal = String.valueOf(state.get("lastGoal"));
    }

    public void setLastGoal(String lastGoal) {
        this.lastGoal = lastGoal;
    }

    public String getLastGoal() {
        return lastGoal;
    }

    public int size() {
        int total = 0;
        for (List<MemoryEntry> list : entries.values()) total += list.size();
        return total;
    }
}
