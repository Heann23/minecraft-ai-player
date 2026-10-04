package me.herry.minecraftAI.ai.world;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 가 세계에 대해 오래 기억하는 구조화된 지식: 거점, 포탈, 요새, 죽은 자리, 탐험한 지역, 상자 내용물.
 * MemorySystem 이 "어디에 무슨 블록이 있었다" 같은 낱개 기억(대부분 만료됨)을 맡는다면, 여기는 만료되지 않는 큰 그림을 맡는다.
 * Bukkit 에 의존하지 않으며 서버 재시작 후에도 유지되도록 저장/복원된다.
 */
public final class WorldModel {
    public enum PortalKind { NETHER, END }

    public record Place(UUID world, BlockPoint pos) {
    }

    public record Portal(PortalKind kind, UUID world, BlockPoint pos) {
    }

    private static final int MAX_DEATHS = 8;
    private static final int MAX_PORTALS = 16;
    private static final int MAX_EXPLORED_CHUNKS = 4096;
    // 같은 포탈의 다른 블록을 또 기록하지 않도록, 이 거리 안의 포탈은 하나로 본다.
    private static final double SAME_PORTAL_RANGE = 6.0;
    private static final int[][] SECTORS = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
    private static final int SECTOR_NEAR = 2;
    private static final int SECTOR_FAR = 5;

    private @Nullable Base home;
    private final List<Portal> portals = new ArrayList<>();
    private @Nullable Place stronghold;
    private boolean endPortalReady;
    private boolean dragonDefeated;
    private final Deque<Place> deaths = new ArrayDeque<>();
    // 월드별로 지나가 본 청크. 넣은 순서를 유지해서 한도를 넘으면 가장 오래된 것부터 잊는다.
    private final Map<UUID, Set<Long>> explored = new HashMap<>();
    // 상자별로 마지막에 열어 봤을 때의 내용물 (Material 이름 -> 개수)
    private final Map<Place, Map<String, Integer>> storage = new LinkedHashMap<>();
    private boolean storageFull;

    public @Nullable Base getHome() {
        return home;
    }

    public void setHome(@Nullable Base home) {
        this.home = home;
    }

    // 그 월드에 있는 거점. 거점이 다른 월드에 있으면 null (네더에서 오버월드의 집으로 걸어갈 수는 없다).
    public @Nullable Base homeIn(UUID world) {
        return home != null && home.world().equals(world) ? home : null;
    }

    /**
     * 거점이 아직 없으면 이 자리를 임시 거점으로 삼는다. 처음 작업대를 놓은 자리가 여기에 해당한다.
     */
    public Base ensureHome(UUID world, BlockPoint center) {
        if (home == null) home = new Base(world, center);
        return home;
    }

    public void rememberPortal(PortalKind kind, UUID world, BlockPoint pos) {
        for (Portal portal : portals) {
            if (portal.kind() == kind && portal.world().equals(world) && portal.pos().distance(pos) <= SAME_PORTAL_RANGE) return;
        }
        portals.add(new Portal(kind, world, pos));
        if (portals.size() > MAX_PORTALS) portals.removeFirst();
    }

    public void forgetPortal(UUID world, BlockPoint pos) {
        portals.removeIf(portal -> portal.world().equals(world) && portal.pos().distance(pos) <= SAME_PORTAL_RANGE);
    }

    public @Nullable Portal nearestPortal(PortalKind kind, UUID world, BlockPoint from) {
        Portal best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Portal portal : portals) {
            if (portal.kind() != kind || !portal.world().equals(world)) continue;
            double distance = portal.pos().distanceSq(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = portal;
            }
        }
        return best;
    }

    public List<Portal> getPortals() {
        return List.copyOf(portals);
    }

    public boolean hasNetherPortal() {
        for (Portal portal : portals) {
            if (portal.kind() == PortalKind.NETHER) return true;
        }
        return false;
    }

    public @Nullable Place getStronghold() {
        return stronghold;
    }

    public void setStronghold(@Nullable Place stronghold) {
        this.stronghold = stronghold;
    }

    public boolean isEndPortalReady() {
        return endPortalReady;
    }

    public void setEndPortalReady(boolean endPortalReady) {
        this.endPortalReady = endPortalReady;
    }

    public boolean isDragonDefeated() {
        return dragonDefeated;
    }

    public void setDragonDefeated(boolean dragonDefeated) {
        this.dragonDefeated = dragonDefeated;
    }

    public void recordDeath(UUID world, BlockPoint pos) {
        deaths.addLast(new Place(world, pos));
        if (deaths.size() > MAX_DEATHS) deaths.removeFirst();
    }

    public List<Place> getDeaths() {
        return List.copyOf(deaths);
    }

    public @Nullable Place lastDeath() {
        return deaths.peekLast();
    }

    public void markExplored(UUID world, int chunkX, int chunkZ) {
        Set<Long> chunks = explored.computeIfAbsent(world, key -> new LinkedHashSet<>());
        if (!chunks.add(chunkKey(chunkX, chunkZ))) return;
        if (chunks.size() > MAX_EXPLORED_CHUNKS) chunks.remove(chunks.iterator().next());
    }

    public boolean isExplored(UUID world, int chunkX, int chunkZ) {
        Set<Long> chunks = explored.get(world);
        return chunks != null && chunks.contains(chunkKey(chunkX, chunkZ));
    }

    public int exploredCount(UUID world) {
        Set<Long> chunks = explored.get(world);
        return chunks == null ? 0 : chunks.size();
    }

    /**
     * 지금 있는 청크에서 봤을 때 가장 덜 가 본 방향. 여덟 방향으로 2~5청크 떨어진 곳을 살펴서 가 본 청크가 가장 적은 쪽을 고른다.
     * 탐험할 때 이미 훑어본 곳을 맴돌지 않게 하는 데 쓴다.
     *
     * @return {dx, dz} 방향 (각각 -1, 0, 1)
     */
    public int[] leastExploredDirection(UUID world, int chunkX, int chunkZ) {
        int[] best = SECTORS[0];
        int bestCount = Integer.MAX_VALUE;
        for (int[] sector : SECTORS) {
            int count = 0;
            for (int distance = SECTOR_NEAR; distance <= SECTOR_FAR; distance++) {
                // 그 방향의 좌우 한 칸씩까지 함께 본다.
                for (int side = -1; side <= 1; side++) {
                    int x = chunkX + sector[0] * distance - sector[1] * side;
                    int z = chunkZ + sector[1] * distance + sector[0] * side;
                    if (isExplored(world, x, z)) count++;
                }
            }
            if (count < bestCount) {
                bestCount = count;
                best = sector;
            }
        }
        return best.clone();
    }

    public void rememberContents(UUID world, BlockPoint chest, Map<String, Integer> contents) {
        storage.put(new Place(world, chest), Map.copyOf(contents));
    }

    public void forgetStorage(UUID world, BlockPoint chest) {
        storage.remove(new Place(world, chest));
    }

    public Map<String, Integer> contentsOf(UUID world, BlockPoint chest) {
        return storage.getOrDefault(new Place(world, chest), Map.of());
    }

    // 기억하고 있는 모든 상자에 든 그 아이템의 개수
    public int storedCount(String material) {
        int total = 0;
        for (Map<String, Integer> contents : storage.values()) total += contents.getOrDefault(material, 0);
        return total;
    }

    // 그 아이템이 가장 많이 든 상자. 없으면 null.
    public @Nullable Place chestHolding(String material) {
        Place best = null;
        int bestCount = 0;
        for (Map.Entry<Place, Map<String, Integer>> entry : storage.entrySet()) {
            int count = entry.getValue().getOrDefault(material, 0);
            if (count > bestCount) {
                bestCount = count;
                best = entry.getKey();
            }
        }
        return best;
    }

    public Map<Place, Map<String, Integer>> getStorage() {
        return Map.copyOf(storage);
    }

    // 상자가 가득 차서 더 넣을 수 없는지. 무언가를 꺼내면 풀린다. 재시작하면 다시 확인하므로 저장하지 않는다.
    public boolean isStorageFull() {
        return storageFull;
    }

    public void markStorageFull(boolean full) {
        storageFull = full;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public Map<String, Object> exportState() {
        return WorldModelCodec.export(this, explored);
    }

    // 저장된 내용 중 읽을 수 없는 항목은 건너뛴다. 저장 파일이 일부 망가졌다고 전부 버리지는 않는다.
    public void importState(Map<String, Object> state) {
        WorldModelCodec.restore(this, state);
    }
}
