package me.herry.minecraftAI.ai.world;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 의 거점(집). 작업대 좌표 하나가 아니라, 중심 위치와 안전 범위, 그 안의 시설(작업대, 화로, 침대, 상자, 포탈)과
 * 건물 영역을 함께 가진다. Bukkit 에 의존하지 않는다.
 */
public final class Base {
    public static final int DEFAULT_SAFE_RADIUS = 24;

    private final UUID world;
    // 건물 안에서 서 있을 칸. 건물이 없을 때는 처음 자리 잡은 곳이다.
    private BlockPoint center;
    private int safeRadius = DEFAULT_SAFE_RADIUS;
    private @Nullable BlockPoint workbench;
    private @Nullable BlockPoint furnace;
    private @Nullable BlockPoint bed;
    private @Nullable BlockPoint entrance;
    private @Nullable BlockPoint portal;
    private final List<BlockPoint> chests = new ArrayList<>();
    // 건물이 차지하는 영역 (벽과 지붕 포함). 건물을 짓기 전에는 null.
    private @Nullable BlockPoint min;
    private @Nullable BlockPoint max;
    // 벽과 지붕이 다 지어져서 밤을 안전하게 보낼 수 있는지
    private boolean sheltered;

    public Base(UUID world, BlockPoint center) {
        this.world = world;
        this.center = center;
    }

    public UUID world() {
        return world;
    }

    public BlockPoint center() {
        return center;
    }

    public void setCenter(BlockPoint center) {
        this.center = center;
    }

    public int safeRadius() {
        return safeRadius;
    }

    public void setSafeRadius(int safeRadius) {
        this.safeRadius = Math.max(4, safeRadius);
    }

    public @Nullable BlockPoint workbench() {
        return workbench;
    }

    public void setWorkbench(@Nullable BlockPoint workbench) {
        this.workbench = workbench;
    }

    public @Nullable BlockPoint furnace() {
        return furnace;
    }

    public void setFurnace(@Nullable BlockPoint furnace) {
        this.furnace = furnace;
    }

    public @Nullable BlockPoint bed() {
        return bed;
    }

    public void setBed(@Nullable BlockPoint bed) {
        this.bed = bed;
    }

    public @Nullable BlockPoint entrance() {
        return entrance;
    }

    public void setEntrance(@Nullable BlockPoint entrance) {
        this.entrance = entrance;
    }

    public @Nullable BlockPoint portal() {
        return portal;
    }

    public void setPortal(@Nullable BlockPoint portal) {
        this.portal = portal;
    }

    public List<BlockPoint> chests() {
        return List.copyOf(chests);
    }

    public void addChest(BlockPoint chest) {
        if (!chests.contains(chest)) chests.add(chest);
    }

    public void removeChest(BlockPoint chest) {
        chests.remove(chest);
    }

    public boolean isSheltered() {
        return sheltered;
    }

    public void setSheltered(boolean sheltered) {
        this.sheltered = sheltered;
    }

    public void setBounds(BlockPoint min, BlockPoint max) {
        this.min = min;
        this.max = max;
    }

    public void clearBounds() {
        this.min = null;
        this.max = null;
    }

    public boolean hasBuilding() {
        return min != null && max != null;
    }

    // 건물 영역(벽 포함) 안의 칸인지. 건물이 없으면 항상 false.
    public boolean isInsideBuilding(UUID world, BlockPoint point) {
        if (min == null || max == null || !this.world.equals(world)) return false;
        return point.x() >= min.x() && point.x() <= max.x()
                && point.y() >= min.y() && point.y() <= max.y()
                && point.z() >= min.z() && point.z() <= max.z();
    }

    // 거점의 안전 범위 안인지. 높이 차이는 보지 않는다 (집 밑 광산도 거점 근처다).
    public boolean isInSafeRange(UUID world, BlockPoint point) {
        if (!this.world.equals(world)) return false;
        double dx = point.x() - center.x();
        double dz = point.z() - center.z();
        return dx * dx + dz * dz <= (double) safeRadius * safeRadius;
    }

    public Map<String, Object> exportState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("world", world.toString());
        state.put("center", center.encode());
        state.put("safeRadius", safeRadius);
        state.put("sheltered", sheltered);
        putPoint(state, "workbench", workbench);
        putPoint(state, "furnace", furnace);
        putPoint(state, "bed", bed);
        putPoint(state, "entrance", entrance);
        putPoint(state, "portal", portal);
        putPoint(state, "min", min);
        putPoint(state, "max", max);
        List<String> encoded = new ArrayList<>();
        for (BlockPoint chest : chests) encoded.add(chest.encode());
        state.put("chests", encoded);
        return state;
    }

    // 저장된 내용이 망가져 있으면 null. 일부 시설 좌표만 잘못됐으면 그 시설만 빼고 불러온다.
    public static @Nullable Base importState(Map<String, Object> state) {
        try {
            Base base = new Base(UUID.fromString(String.valueOf(state.get("world"))), BlockPoint.parse(String.valueOf(state.get("center"))));
            if (state.get("safeRadius") instanceof Number radius) base.setSafeRadius(radius.intValue());
            base.sheltered = Boolean.TRUE.equals(state.get("sheltered"));
            base.workbench = point(state, "workbench");
            base.furnace = point(state, "furnace");
            base.bed = point(state, "bed");
            base.entrance = point(state, "entrance");
            base.portal = point(state, "portal");
            base.min = point(state, "min");
            base.max = point(state, "max");
            if (base.min == null || base.max == null) base.clearBounds();
            if (state.get("chests") instanceof List<?> list) {
                for (Object item : list) {
                    BlockPoint chest = parse(item);
                    if (chest != null) base.addChest(chest);
                }
            }
            return base;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void putPoint(Map<String, Object> state, String key, @Nullable BlockPoint point) {
        if (point != null) state.put(key, point.encode());
    }

    private static @Nullable BlockPoint point(Map<String, Object> state, String key) {
        return parse(state.get(key));
    }

    private static @Nullable BlockPoint parse(@Nullable Object value) {
        if (value == null) return null;
        try {
            return BlockPoint.parse(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
