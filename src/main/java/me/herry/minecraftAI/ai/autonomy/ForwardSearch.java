package me.herry.minecraftAI.ai.autonomy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Incremental, bounded uniform-cost search. Returned effects are predictions: runtime must observe real outcomes.
 * Preparation and every outgoing edge yield to the same caller-owned work budget.
 */
public final class ForwardSearch {
    public static final int MAX_NODES = 4096;
    public static final int MAX_DEPTH = 48;

    public enum Status { SEARCHING, SOLVED, UNREACHABLE, LIMIT_REACHED }

    private static final class Node {
        final PlanningState state;
        final double cost;
        final int depth;
        final int serial;
        final Node parent;
        final ActionModel action;
        boolean active = true;

        Node(PlanningState state, double cost, int depth, int serial, Node parent, ActionModel action) {
            this.state = state;
            this.cost = cost;
            this.depth = depth;
            this.serial = serial;
            this.parent = parent;
            this.action = action;
        }
    }

    private final PlanningState initial;
    private final List<ActionModel> available;
    private final AutonomyGoal goal;
    private final boolean[] relevant;
    private final Set<String> needed = new HashSet<>();
    private final List<ActionModel> operators = new ArrayList<>();
    private final Map<String, Integer> caps = new HashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator
            .comparingDouble((Node node) -> node.cost).thenComparingInt(node -> node.serial));
    // Keep a cost/depth Pareto frontier. A cheaper but deeper route must not discard a shallow route
    // that can still finish within MAX_DEPTH.
    private final Map<PlanningState, List<Node>> labels = new HashMap<>();
    private Status status = Status.SEARCHING;
    private List<ActionModel> result = List.of();
    private double resultCost = Double.POSITIVE_INFINITY;
    private int closureIndex;
    private boolean closureChanged;
    private boolean closureReady;
    private int capsIndex;
    private boolean prepared;
    private int generated;
    private int expanded;
    private boolean depthOrNumericLimit;
    private Node current;
    private int edgeIndex;

    public ForwardSearch(PlanningState initial, List<ActionModel> actions, AutonomyGoal goal) {
        this.initial = Objects.requireNonNull(initial, "initial");
        var sorted = new ArrayList<>(List.copyOf(Objects.requireNonNull(actions, "actions")));
        sorted.sort(Comparator.comparing(ActionModel::id));
        this.available = List.copyOf(sorted);
        this.goal = Objects.requireNonNull(goal, "goal");
        relevant = new boolean[available.size()];
        needed.addAll(goal.required().keySet());
        caps.putAll(initial.quantities());
        goal.required().forEach((token, amount) -> caps.merge(token, amount, Math::max));
        if (goal.achieved(initial)) {
            status = Status.SOLVED;
            resultCost = 0;
        }
    }

    public Status status() { return status; }
    public List<ActionModel> plan() { return result; }
    public double cost() { return resultCost; }
    public int expanded() { return expanded; }

    /** maxExpansions limits newly opened states, not resumed edges; zero does no work. */
    public Status advance(int maxExpansions, BooleanSupplier budgetExhausted) {
        if (maxExpansions < 0) throw new IllegalArgumentException("maxExpansions must be nonnegative");
        Objects.requireNonNull(budgetExhausted, "budgetExhausted");
        if (status != Status.SEARCHING || maxExpansions == 0) return status;
        if (!prepare(budgetExhausted)) return status;

        int newlyExpanded = 0;
        while (status == Status.SEARCHING) {
            if (budgetExhausted.getAsBoolean()) return status;
            if (current == null) {
                if (newlyExpanded >= maxExpansions) return status;
                Node next = open.poll();
                if (next == null) {
                    status = depthOrNumericLimit ? Status.LIMIT_REACHED : Status.UNREACHABLE;
                    return status;
                }
                if (!next.active) continue;
                if (goal.achieved(next.state)) {
                    solve(next);
                    return status;
                }
                expanded++;
                newlyExpanded++;
                if (next.depth == MAX_DEPTH) {
                    depthOrNumericLimit = true;
                    continue;
                }
                current = next;
                edgeIndex = 0;
            }

            while (edgeIndex < operators.size()) {
                if (budgetExhausted.getAsBoolean()) return status;
                ActionModel action = operators.get(edgeIndex++);
                if (!current.state.satisfies(action.preconditions())) continue;
                PlanningState next = applyCapped(current.state, action);
                if (next.equals(current.state)) continue;
                double cost = current.cost + action.cost();
                if (!Double.isFinite(cost)) {
                    depthOrNumericLimit = true;
                    continue;
                }
                admit(next, cost, current.depth + 1, current, action);
                if (status != Status.SEARCHING) return status;
            }
            current = null;
        }
        return status;
    }

    private boolean prepare(BooleanSupplier budgetExhausted) {
        if (prepared) return true;
        while (!closureReady) {
            if (budgetExhausted.getAsBoolean()) return false;
            if (closureIndex == available.size()) {
                if (closureChanged) {
                    closureChanged = false;
                    closureIndex = 0;
                    continue;
                }
                closureReady = true;
                for (int index = 0; index < available.size(); index++) {
                    if (relevant[index]) operators.add(available.get(index));
                }
                break;
            }
            int index = closureIndex++;
            ActionModel action = available.get(index);
            if (relevant[index] || !producesNeeded(action)) continue;
            relevant[index] = true;
            if (needed.addAll(action.preconditions().keySet())) closureChanged = true;
        }
        while (capsIndex < operators.size()) {
            if (budgetExhausted.getAsBoolean()) return false;
            ActionModel action = operators.get(capsIndex++);
            action.preconditions().forEach((token, amount) -> caps.merge(token, amount, Math::max));
            action.effects().forEach((token, amount) -> caps.merge(token, amount, Math::max));
            action.consumed().forEach((token, amount) -> {
                long boundedNeed = (long) MAX_DEPTH * amount + goal.required().getOrDefault(token, 0);
                caps.merge(token, (int) Math.min(Integer.MAX_VALUE, boundedNeed), Math::max);
            });
        }
        if (budgetExhausted.getAsBoolean()) return false;
        Node first = new Node(initial, 0, 0, 0, null, null);
        open.add(first);
        labels.put(initial, new ArrayList<>(List.of(first)));
        generated = 1;
        prepared = true;
        return true;
    }

    private boolean producesNeeded(ActionModel action) {
        for (var effect : action.effects().entrySet()) {
            if (needed.contains(effect.getKey()) && effect.getValue() > action.consumed().getOrDefault(effect.getKey(), 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A <=48-step route can consume at most 48*maxStepConsumption plus its final required amount.
     * Initial stock, every prerequisite and every effect batch are also retained. In particular a
     * never-consumed fact whose initial/goal/precondition/effect counts are all <=1 has cap 1.
     */
    private PlanningState applyCapped(PlanningState state, ActionModel action) {
        Map<String, Integer> next = new TreeMap<>(state.quantities());
        action.consumed().forEach((token, amount) -> next.put(token, next.getOrDefault(token, 0) - amount));
        action.effects().forEach((token, amount) -> {
            long quantity = (long) next.getOrDefault(token, 0) + amount;
            next.put(token, (int) Math.min(quantity, caps.getOrDefault(token, 0)));
        });
        return new PlanningState(next);
    }

    private void admit(PlanningState state, double cost, int depth, Node parent, ActionModel action) {
        List<Node> known = labels.get(state);
        if (known != null) {
            for (Node prior : known) {
                if (prior.cost <= cost && prior.depth <= depth) return;
            }
        }
        if (generated == MAX_NODES) {
            status = Status.LIMIT_REACHED;
            return;
        }
        Node next = new Node(state, cost, depth, generated++, parent, action);
        if (known == null) {
            known = new ArrayList<>();
            labels.put(state, known);
        } else {
            known.removeIf(prior -> {
                if (cost <= prior.cost && depth <= prior.depth) {
                    prior.active = false;
                    return true;
                }
                return false;
            });
        }
        known.add(next);
        open.add(next);
    }

    private void solve(Node node) {
        List<ActionModel> actions = new ArrayList<>();
        for (Node cursor = node; cursor.parent != null; cursor = cursor.parent) actions.add(cursor.action);
        Collections.reverse(actions);
        result = List.copyOf(actions);
        resultCost = node.cost;
        status = Status.SOLVED;
    }
}
