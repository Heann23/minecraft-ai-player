package me.herry.minecraftAI.persist;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI 한 명의 저장 상태. 서버를 재시작한 뒤 같은 AI 를 되살리는 데 필요한 것만 담는다.
 * 각 시스템(기억, 월드 모델, 몸 상태 등)은 자기 상태를 단순한 값(문자열, 숫자, 목록, 맵)의 맵으로 내놓고,
 * 그것이 sections 에 이름별로 들어간다. Bukkit 에 의존하지 않는다.
 */
public final class AISnapshot {
    public static final int VERSION = 1;

    public String name = "";
    public @Nullable String skinValue;
    public @Nullable String skinSignature;

    public @Nullable UUID world;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;

    // 저장될 때 자율 행동 중이었는지. 되살린 뒤에 이어서 움직일지를 정한다.
    public boolean running;
    // AI 틱. 기억의 만료 시각이 이 값을 기준으로 하므로 이어서 세야 한다.
    public long ticks;

    public final Map<String, Map<String, Object>> sections = new LinkedHashMap<>();

    public Map<String, Object> section(String name) {
        return sections.getOrDefault(name, Map.of());
    }
}
