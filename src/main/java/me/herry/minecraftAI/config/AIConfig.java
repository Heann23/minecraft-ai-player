package me.herry.minecraftAI.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.function.Consumer;

/**
 * config.yml 값을 한 번 읽어서 보관한다. 잘못된 값은 안전한 범위로 보정하고,
 * 값 사이의 관계가 맞지 않으면(ConfigRules) 고친 뒤 경고를 남긴다.
 */
public final class AIConfig {
    public final int updateInterval;
    public final int maxCount;
    public final String defaultName;
    public final int viewDistance;
    public final int respawnDelay;
    public final boolean showGoalLabel;

    public final int blockRange;
    public final int blockVerticalRange;
    public final int entityRange;
    public final int blocksPerTick;
    public final int rescanInterval;

    public final int maxPathDistance;
    public final int maxPathNodes;
    public final int nodesPerTick;
    public final int stuckTimeout;
    public final int maxRepaths;

    public final int lowHealth;
    public final int criticalHealth;
    public final int eatBelow;

    public final double engageRange;
    public final double fleeDistance;

    public final boolean chatEnabled;
    public final int chatMinInterval;

    // 한 서버 틱에 모든 AI 가 미룰 수 있는 일(블록 검색, 경로 탐색)에 쓸 수 있는 시간. 0 이면 제한하지 않는다.
    public final long tickBudgetNanos;

    public final boolean persistenceEnabled;
    public final int autosaveInterval;

    public final boolean debugEnabled;

    /**
     * @param warn 관계가 맞지 않는 값을 고쳤을 때 그 내용을 받는다 (보통 플러그인 로거의 warning)
     */
    public AIConfig(FileConfiguration config, Consumer<String> warn) {
        updateInterval = clamp(config.getInt("ai.update-interval", 5), 1, 100);
        maxCount = clamp(config.getInt("ai.max-count", 1), 1, 20);
        defaultName = config.getString("ai.default-name", "AI_Player");
        viewDistance = clamp(config.getInt("ai.view-distance", 4), 2, 10);
        respawnDelay = clamp(config.getInt("ai.respawn-delay", 60), 30, 1200);
        showGoalLabel = config.getBoolean("ai.show-goal-label", true);

        blockRange = clamp(config.getInt("perception.block-range", 16), 4, 32);
        blockVerticalRange = clamp(config.getInt("perception.block-vertical-range", 8), 2, 16);
        entityRange = clamp(config.getInt("perception.entity-range", 32), 4, 48);
        blocksPerTick = clamp(config.getInt("perception.blocks-per-tick", 800), 50, 5000);
        rescanInterval = clamp(config.getInt("perception.rescan-interval", 200), 20, 6000);

        maxPathDistance = clamp(config.getInt("navigation.max-path-distance", 64), 8, 128);
        maxPathNodes = clamp(config.getInt("navigation.max-path-nodes", 4000), 200, 20000);
        nodesPerTick = ConfigRules.nodesPerTick(clamp(config.getInt("navigation.nodes-per-tick", 400), 20, 5000), maxPathNodes, warn);
        stuckTimeout = clamp(config.getInt("navigation.stuck-timeout", 100), 40, 1200);
        maxRepaths = clamp(config.getInt("navigation.max-repaths", 3), 0, 10);

        lowHealth = clamp(config.getInt("survival.low-health", 8), 1, 20);
        criticalHealth = ConfigRules.criticalHealth(clamp(config.getInt("survival.critical-health", 4), 1, 20), lowHealth, warn);
        eatBelow = clamp(config.getInt("survival.eat-below", 14), 1, 20);

        ConfigRules.CombatRanges ranges = ConfigRules.combatRanges(
                clamp(config.getInt("combat.engage-range", 16), 2, 48),
                clamp(config.getInt("combat.flee-distance", 24), 4, (int) ConfigRules.MAX_FLEE_DISTANCE),
                entityRange, warn);
        engageRange = ranges.engageRange();
        fleeDistance = ranges.fleeDistance();

        chatEnabled = config.getBoolean("chat.enabled", true);
        chatMinInterval = clamp(config.getInt("chat.min-interval", 100), 0, 6000);

        double budgetMillis = Math.max(0.0, Math.min(40.0, config.getDouble("performance.tick-budget-ms", 5.0)));
        tickBudgetNanos = (long) (budgetMillis * 1_000_000.0);

        persistenceEnabled = config.getBoolean("persistence.enabled", true);
        autosaveInterval = clamp(config.getInt("persistence.autosave-interval", 6000), 200, 72000);

        debugEnabled = config.getBoolean("debug.enabled", false);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
