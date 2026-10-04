package me.herry.minecraftAI.persist;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AISnapshot 을 저장 형식과 무관한 맵으로 바꾸고 되돌린다. YAML 이든 JSON 이든 이 맵을 그대로 쓰면 된다.
 * Bukkit 에 의존하지 않는다.
 */
public final class SnapshotCodec {
    private SnapshotCodec() {
    }

    public static Map<String, Object> toMap(AISnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", AISnapshot.VERSION);
        map.put("name", snapshot.name);
        if (snapshot.skinValue != null) map.put("skinValue", snapshot.skinValue);
        if (snapshot.skinSignature != null) map.put("skinSignature", snapshot.skinSignature);
        if (snapshot.world != null) map.put("world", snapshot.world.toString());
        map.put("x", snapshot.x);
        map.put("y", snapshot.y);
        map.put("z", snapshot.z);
        map.put("yaw", (double) snapshot.yaw);
        map.put("pitch", (double) snapshot.pitch);
        map.put("running", snapshot.running);
        map.put("ticks", snapshot.ticks);
        map.put("sections", new LinkedHashMap<>(snapshot.sections));
        return map;
    }

    /**
     * @return 이름이 없거나 더 새로운 형식으로 저장된 것이라 읽을 수 없으면 null
     */
    @SuppressWarnings("unchecked")
    public static @Nullable AISnapshot fromMap(Map<String, Object> map) {
        if (!(map.get("name") instanceof String name) || name.isBlank()) return null;
        if (number(map.get("version"), 1).intValue() > AISnapshot.VERSION) return null;

        AISnapshot snapshot = new AISnapshot();
        snapshot.name = name;
        snapshot.skinValue = map.get("skinValue") instanceof String value ? value : null;
        snapshot.skinSignature = map.get("skinSignature") instanceof String signature ? signature : null;
        snapshot.world = uuid(map.get("world"));
        snapshot.x = number(map.get("x"), 0).doubleValue();
        snapshot.y = number(map.get("y"), 0).doubleValue();
        snapshot.z = number(map.get("z"), 0).doubleValue();
        snapshot.yaw = number(map.get("yaw"), 0).floatValue();
        snapshot.pitch = number(map.get("pitch"), 0).floatValue();
        snapshot.running = Boolean.TRUE.equals(map.get("running"));
        snapshot.ticks = number(map.get("ticks"), 0).longValue();
        if (map.get("sections") instanceof Map<?, ?> sections) {
            for (Map.Entry<?, ?> entry : sections.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> section) {
                    snapshot.sections.put(String.valueOf(entry.getKey()), (Map<String, Object>) section);
                }
            }
        }
        return snapshot;
    }

    private static Number number(@Nullable Object value, Number fallback) {
        return value instanceof Number number ? number : fallback;
    }

    private static @Nullable UUID uuid(@Nullable Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
