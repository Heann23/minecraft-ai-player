package me.herry.minecraftAI.ai.autonomy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Bounded backward search for an executable contribution when a full plan is unavailable.
 * A choice is partial support for the original objective, never proof of a complete or optimal route.
 */
public final class SupportSearch {
    public static final int MAX_NODES = 512;
    public static final int MAX_DEPTH = 24;
    public static final int MAX_CHOICES = 8;

    public enum Status { SEARCHING, READY, NO_CANDIDATE, LIMIT_REACHED }

    public record Choice(ActionModel action, double cost, List<String> dependencyPath) {
        public Choice {
            Objects.requireNonNull(action, "action");
            if (!Double.isFinite(cost) || cost <= 0) throw new IllegalArgumentException("cost must be finite and positive");
            dependencyPath = List.copyOf(dependencyPath);
        }
    }

    private record Need(String token, int amount, double downstreamCost, int depth,
                        List<String> dependencyPath, Set<String> pathTokens) {}
    private record NeedKey(String token, int amount, Set<String> pathTokens) {}

    private final PlanningState initial;
    private final List<ActionModel> actions;
    private final ArrayDeque<Need> open = new ArrayDeque<>();
    private final Map<NeedKey, Double> bestNeeds = new HashMap<>();
    private final Map<String, Choice> candidates = new LinkedHashMap<>();
    private Status status = Status.SEARCHING;
    private int generated;
    private boolean limited;
    private Need current;
    private int producerIndex;
    private ActionModel producer;
    private List<Map.Entry<String, Integer>> prerequisites = List.of();
    private int prerequisiteIndex;
    private long repetitions;
    private double routeCost;
    private List<String> route = List.of();

    public SupportSearch(PlanningState initial, List<ActionModel> actions, AutonomyGoal goal) {
        this.initial = Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(goal, "goal");
        var ordered = new ArrayList<>(List.copyOf(Objects.requireNonNull(actions, "actions")));
        ordered.sort(Comparator.comparingDouble(ActionModel::cost).thenComparing(ActionModel::id));
        this.actions = List.copyOf(ordered);
        for (var requirement : goal.required().entrySet()) {
            if (initial.count(requirement.getKey()) >= requirement.getValue()) continue;
            enqueue(new Need(requirement.getKey(), requirement.getValue(), 0, 0,
                    List.of(), Set.of(requirement.getKey())));
        }
        if (open.isEmpty()) finish();
    }

    public Status status() { return status; }

    /** Paths list producer IDs from the original objective toward the selected executable leaf. */
    public List<Choice> choices() {
        return candidates.values().stream().sorted(Comparator.comparingDouble(Choice::cost)
                .thenComparing(choice -> choice.action().id())).toList();
    }

    /** Limits newly opened demand nodes; an interrupted producer/prerequisite resumes on the next call. */
    public Status advance(int maxNodes, BooleanSupplier budgetExhausted) {
        if (maxNodes < 0) throw new IllegalArgumentException("maxNodes must be nonnegative");
        Objects.requireNonNull(budgetExhausted, "budgetExhausted");
        if (status != Status.SEARCHING || maxNodes == 0) return status;
        int opened = 0;
        while (status == Status.SEARCHING) {
            if (budgetExhausted.getAsBoolean()) return status;
            if (current == null) {
                if (open.isEmpty()) { finish(); return status; }
                if (opened >= maxNodes) return status;
                current = open.removeFirst();
                opened++;
                if (current.downstreamCost() > bestNeeds.get(key(current))) { current = null; continue; }
                producerIndex = 0;
            }

            if (producer != null) {
                if (prerequisiteIndex < prerequisites.size()) {
                    var requirement = prerequisites.get(prerequisiteIndex++);
                    long amount = requirement.getValue() + (repetitions - 1L)
                            * producer.consumed().getOrDefault(requirement.getKey(), 0);
                    if (amount > Integer.MAX_VALUE) { limited = true; continue; }
                    String token = requirement.getKey();
                    if (initial.count(token) >= amount || current.pathTokens().contains(token)) continue;
                    if (current.depth() + 1 >= MAX_DEPTH) { limited = true; continue; }
                    var pathTokens = new java.util.HashSet<>(current.pathTokens());
                    pathTokens.add(token);
                    enqueue(new Need(token, (int) amount, routeCost, current.depth() + 1,
                            route, Set.copyOf(pathTokens)));
                    continue;
                }
                producer = null;
                prerequisites = List.of();
                continue;
            }

            if (producerIndex >= actions.size()) { current = null; continue; }
            ActionModel candidate = actions.get(producerIndex++);
            long gain = (long) candidate.effects().getOrDefault(current.token(), 0)
                    - candidate.consumed().getOrDefault(current.token(), 0);
            if (gain <= 0) continue;
            long missing = (long) current.amount() - initial.count(current.token());
            if (missing <= 0) continue;
            repetitions = (missing + gain - 1L) / gain;
            routeCost = current.downstreamCost() + repetitions * candidate.cost();
            if (!Double.isFinite(routeCost)) { limited = true; continue; }
            var path = new ArrayList<>(current.dependencyPath());
            path.add(candidate.id());
            route = List.copyOf(path);
            if (initial.satisfies(candidate.preconditions())) {
                Choice choice = new Choice(candidate, routeCost, route);
                Choice prior = candidates.get(candidate.id());
                if (prior == null || choice.cost() < prior.cost()) candidates.put(candidate.id(), choice);
                if (candidates.size() >= MAX_CHOICES) status = Status.READY;
                continue;
            }
            producer = candidate;
            prerequisites = List.copyOf(candidate.preconditions().entrySet());
            prerequisiteIndex = 0;
        }
        return status;
    }

    private static NeedKey key(Need need) { return new NeedKey(need.token(), need.amount(), need.pathTokens()); }

    private void enqueue(Need need) {
        NeedKey key = key(need);
        Double best = bestNeeds.get(key);
        if (best != null && best <= need.downstreamCost()) return;
        if (generated >= MAX_NODES) { limited = true; return; }
        generated++;
        bestNeeds.put(key, need.downstreamCost());
        open.addLast(need);
    }

    private void finish() {
        status = !candidates.isEmpty() ? Status.READY : limited ? Status.LIMIT_REACHED : Status.NO_CANDIDATE;
    }
}
