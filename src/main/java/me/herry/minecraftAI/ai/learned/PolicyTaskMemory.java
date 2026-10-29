package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.goal.model.AcquireGoal;
import org.bukkit.Material;
import java.util.*;

/** Bounded working memory. Describes an objective and actual outcomes; never chooses actions or awards rewards. */
public final class PolicyTaskMemory {
    public record Objective(UUID id, UUID world, AcquireGoal goal, long startedTick) {}
    public record Outcome(UUID world, long tick, String objectiveId, String action, String status, String reason) {
        public Outcome {
            Objects.requireNonNull(world);
            if (tick < 0 || action == null || action.isBlank() || action.length() > 256
                    || !Set.of("SUCCESS", "FAILED").contains(status) || reason == null || reason.length() > 512)
                throw new IllegalArgumentException("invalid working-memory outcome");
            if (!objectiveId.isEmpty()) UUID.fromString(objectiveId);
        }
    }
    public record View(String schema, Objective objective, boolean worldMatches, int owned, int remaining,
                       boolean currentlyAchieved, List<Outcome> recentOutcomes) {
        public View { recentOutcomes = List.copyOf(recentOutcomes); }
    }
    private Objective objective;
    private final Deque<Outcome> outcomes = new ArrayDeque<>();

    public void acquire(UUID world, String material, int count, long tick) {
        Material exact = Material.matchMaterial(material);
        if (exact == null || exact.isAir() || !exact.isItem() || count < 1 || count > 4096 || tick < 0)
            throw new IllegalArgumentException("exact item, count 1..4096 and valid tick required");
        objective = new Objective(UUID.randomUUID(), Objects.requireNonNull(world), new AcquireGoal(exact.name(), count), tick);
    }
    public void clearObjective() { objective = null; }
    public View observe(UUID world, Map<String, Integer> stock) {
        boolean matches = objective != null && objective.world().equals(world);
        int owned = matches ? Math.max(0, stock.getOrDefault(objective.goal().item(), 0)) : 0;
        return new View("policy-task-memory/v1", objective, matches, owned,
                objective == null ? 0 : Math.max(0, objective.goal().count() - owned),
                matches && owned >= objective.goal().count(), List.copyOf(outcomes));
    }
    public void ended(UUID world, long tick, String action, String status, String reason) {
        outcomes.addLast(new Outcome(world, tick, objective == null ? "" : objective.id().toString(), action, status,
                reason.length() > 512 ? reason.substring(0, 512) : reason));
        while (outcomes.size() > 8) outcomes.removeFirst();
    }
    public Map<String, Object> exportState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "policy-task-memory/v1");
        if (objective != null) result.put("objective", Map.of("id", objective.id().toString(), "world", objective.world().toString(),
                "item", objective.goal().item(), "count", objective.goal().count(), "startedTick", objective.startedTick()));
        result.put("outcomes", outcomes.stream().map(o -> Map.<String, Object>of("world", o.world().toString(), "tick", o.tick(),
                "objectiveId", o.objectiveId(), "action", o.action(), "status", o.status(), "reason", o.reason())).toList());
        return result;
    }
    public void importState(Map<String, Object> state) {
        if (state.isEmpty()) return; // Old snapshots have no working-memory section.
        if (!"policy-task-memory/v1".equals(state.get("schema"))) throw new IllegalArgumentException("working-memory schema mismatch");
        Objective restored = null;
        if (state.containsKey("objective")) {
            if (!(state.get("objective") instanceof Map<?, ?> goal)) throw new IllegalArgumentException("invalid saved objective");
            var checked = new PolicyTaskMemory();
            checked.acquire(UUID.fromString(String.valueOf(goal.get("world"))), String.valueOf(goal.get("item")),
                    Math.toIntExact(integer(goal.get("count"))), integer(goal.get("startedTick")));
            restored = new Objective(UUID.fromString(String.valueOf(goal.get("id"))), checked.objective.world(),
                    checked.objective.goal(), checked.objective.startedTick());
        }
        if (!(state.get("outcomes") instanceof List<?> saved) || saved.size() > 8)
            throw new IllegalArgumentException("invalid saved working-memory history");
        List<Outcome> restoredOutcomes = new ArrayList<>();
        for (Object value : saved) {
            if (!(value instanceof Map<?, ?> row)) throw new IllegalArgumentException("invalid saved outcome");
            restoredOutcomes.add(new Outcome(UUID.fromString(String.valueOf(row.get("world"))), integer(row.get("tick")),
                    String.valueOf(row.get("objectiveId")), String.valueOf(row.get("action")),
                    String.valueOf(row.get("status")), String.valueOf(row.get("reason"))));
        }
        objective = restored; outcomes.clear(); outcomes.addAll(restoredOutcomes);
    }
    private static long integer(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.longValue()
                || number.longValue() < 0) throw new IllegalArgumentException("invalid working-memory integer");
        return number.longValue();
    }
}
