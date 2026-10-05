package me.herry.minecraftAI.ai.comm;

import java.util.Map;

/** Read-only values copied on the server thread. No world/player/item references or custom item metadata. */
public record GameStateSnapshot(String aiName, String state, String goal, String activity, String action,
                                String reason, String dimension, int x, int y, int z,
                                double health, int food, Map<String, Integer> items) {
    public GameStateSnapshot {
        if (aiName == null || !aiName.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("AI name");
        code(state); code(goal); code(action); code(dimension);
        if (!java.util.Set.of("RUNNING", "STOPPED", "DEAD").contains(state)
                || !java.util.Set.of("NORMAL", "NETHER", "THE_END", "CUSTOM").contains(dimension))
            throw new IllegalArgumentException("game state category");
        text(activity, 150); text(reason, 500);
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048
                || !Double.isFinite(health) || health < 0 || health > 1024 || food < 0 || food > 20)
            throw new IllegalArgumentException("game state bounds");
        items = Map.copyOf(items);
        if (items.size() > 41) throw new IllegalArgumentException("inventory bounds");
        items.forEach((material, count) -> {
            if (!material.matches("[A-Z][A-Z0-9_]{0,63}") || count < 1 || count > 1_000_000)
                throw new IllegalArgumentException("inventory values");
        });
    }
    private static void code(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{0,99}")) throw new IllegalArgumentException("game state code");
    }
    private static void text(String value, int bound) {
        if (value == null || value.length() > bound || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("game state text");
    }
}
