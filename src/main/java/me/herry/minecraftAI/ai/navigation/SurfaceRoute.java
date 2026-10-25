package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/** A route over observed, level, breathable water. UNKNOWN is not navigable and is not proof of no water. */
public final class SurfaceRoute {
    public static final int MAX_NODES = 1024;
    public static final int MAX_RANGE = 48;
    public enum Kind { WATER, SHORE, BLOCKED, UNKNOWN, DANGER }
    public enum Status { SEARCHING, FOUND, NO_ROUTE, LIMIT_REACHED }
    @FunctionalInterface public interface Columns { Kind at(int x, int z); }

    private record Node(BlockPoint point, int cost, int estimate, Node parent) {}
    private static final int[][] SIDES = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private final BlockPoint start;
    private final BlockPoint target;
    private final Columns columns;
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt(Node::estimate)
            .thenComparingInt(Node::cost).thenComparingInt(node -> node.point().x()).thenComparingInt(node -> node.point().z()));
    private final Map<BlockPoint, Integer> costs = new HashMap<>();
    private Status status = Status.SEARCHING;
    private List<BlockPoint> path = List.of();
    private Node current;
    private int sideIndex;
    private boolean initialized;
    private boolean hitLimit;
    private boolean unknown;

    public SurfaceRoute(BlockPoint start, BlockPoint target, Columns columns) {
        this.start = Objects.requireNonNull(start, "start");
        this.target = Objects.requireNonNull(target, "target");
        this.columns = Objects.requireNonNull(columns, "columns");
        if (start.y() != target.y() || horizontalSquared(start, target) > MAX_RANGE * MAX_RANGE)
            throw new IllegalArgumentException("surface route requires a nearby level target");
    }

    public Status status() { return status; }
    public List<BlockPoint> path() { return path; }
    public boolean encounteredUnknown() { return unknown; }

    /** Every candidate edge yields to the caller's tick budget; unfinished nodes retain their edge cursor. */
    public Status advance(int maxNodes, BooleanSupplier exhausted) {
        if (maxNodes < 0) throw new IllegalArgumentException("negative node budget");
        Objects.requireNonNull(exhausted, "exhausted");
        if (status != Status.SEARCHING || maxNodes == 0 || exhausted.getAsBoolean()) return status;
        if (!initialized) {
            initialized = true;
            Kind initial = columns.at(start.x(), start.z());
            Kind end = columns.at(target.x(), target.z());
            unknown = initial == Kind.UNKNOWN || end == Kind.UNKNOWN;
            if (!passable(initial) || !passable(end)) { status = Status.NO_ROUTE; return status; }
            costs.put(start, 0);
            open.add(new Node(start, 0, heuristic(start), null));
        }
        int expanded = 0;
        while (status == Status.SEARCHING && !exhausted.getAsBoolean()) {
            if (current == null) {
                if (expanded >= maxNodes) return status;
                current = open.poll();
                if (current == null) { status = hitLimit ? Status.LIMIT_REACHED : Status.NO_ROUTE; return status; }
                if (current.cost() != costs.getOrDefault(current.point(), Integer.MAX_VALUE)) { current = null; continue; }
                if (current.point().equals(target)) { finish(current); return status; }
                sideIndex = 0;
                expanded++;
            }
            while (sideIndex < SIDES.length) {
                if (exhausted.getAsBoolean()) return status;
                int[] side = SIDES[sideIndex++];
                BlockPoint next = current.point().offset(side[0], 0, side[1]);
                if (horizontalSquared(start, next) > MAX_RANGE * MAX_RANGE) continue;
                Kind kind = columns.at(next.x(), next.z());
                if (kind == Kind.UNKNOWN) unknown = true;
                if (!passable(kind) || kind == Kind.SHORE && !next.equals(target)) continue;
                int cost = current.cost() + 1;
                if (cost >= costs.getOrDefault(next, Integer.MAX_VALUE)) continue;
                if (!costs.containsKey(next) && costs.size() >= MAX_NODES) { hitLimit = true; continue; }
                costs.put(next, cost);
                open.add(new Node(next, cost, cost + heuristic(next), current));
            }
            current = null;
        }
        return status;
    }

    public static boolean passable(Kind kind) { return kind == Kind.WATER || kind == Kind.SHORE; }

    /** Success means the requested horizontal destination, plus surface breathing or grounded shore arrival. */
    public static boolean arrived(double x, double y, double z, BlockPoint target, boolean water,
                                  boolean grounded, boolean eyesUnderWater, int air, int maximumAir) {
        double dx = x - (target.x() + 0.5), dz = z - (target.z() + 0.5);
        if (dx * dx + dz * dz > 0.64 || y < target.y() - 0.5 || y > target.y() + 2.0) return false;
        boolean breathing = !eyesUnderWater && air >= Math.max(80, maximumAir * 0.6);
        return breathing && (water || grounded);
    }

    private int heuristic(BlockPoint point) { return Math.abs(point.x() - target.x()) + Math.abs(point.z() - target.z()); }
    private static double horizontalSquared(BlockPoint a, BlockPoint b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }
    private void finish(Node node) {
        List<BlockPoint> result = new ArrayList<>();
        while (node != null) { result.add(node.point()); node = node.parent(); }
        Collections.reverse(result);
        path = List.copyOf(result);
        status = Status.FOUND;
    }
}
