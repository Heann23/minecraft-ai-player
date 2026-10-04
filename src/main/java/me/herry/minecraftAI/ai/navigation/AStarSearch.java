package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 블록 격자 위의 A* 탐색.
 * 한 번에 끝까지 계산하지 않고 advance() 로 조금씩 진행해서 한 틱에 쓰는 시간을 제한한다.
 */
public final class AStarSearch {
    public enum State { RUNNING, FOUND, PARTIAL, FAILED }

    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final double DIAGONAL_COST = 1.414;
    private static final double JUMP_COST = 0.5;
    private static final double DROP_COST_PER_BLOCK = 0.5;
    // 물은 헤엄쳐 건널 수는 있지만 느리고 위험하므로, 돌아갈 길이 있으면 돌아간다.
    private static final double WATER_COST = 6.0;
    private static final double NEAR_DANGER_COST = 6.0;
    // 부분 경로를 쓰려면 최소한 이만큼은 목표에 가까워져야 한다.
    private static final double MIN_PARTIAL_PROGRESS = 1.0;

    private static final class Node {
        final int x, y, z;
        final double g, h;
        final Node parent;
        boolean closed;

        Node(int x, int y, int z, double g, double h, Node parent) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.h = h;
            this.parent = parent;
        }
    }

    private final TerrainView terrain;
    private final PathGoal goal;
    private final int maxNodes;
    private final int maxDistance;
    private final int maxDrop;

    private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.g + a.h, b.g + b.h));
    private final Map<Long, Node> nodes = new HashMap<>();
    private final Node start;
    private Node best;
    private int expanded;
    private State state = State.RUNNING;
    private Path result;

    public AStarSearch(TerrainView terrain, BlockPoint start, PathGoal goal, int maxNodes, int maxDistance, int maxDrop) {
        this.terrain = terrain;
        this.goal = goal;
        this.maxNodes = maxNodes;
        this.maxDistance = maxDistance;
        this.maxDrop = maxDrop;
        this.start = new Node(start.x(), start.y(), start.z(), 0.0, goal.heuristic(start.x(), start.y(), start.z()), null);
        this.best = this.start;
        nodes.put(start.pack(), this.start);
        open.add(this.start);
    }

    /**
     * 최대 budget 개의 노드를 펼친다. 탐색이 끝나면 RUNNING 이 아닌 상태를 반환한다.
     */
    public State advance(int budget) {
        while (state == State.RUNNING && budget-- > 0) {
            Node current = pollNext();
            if (current == null) {
                finishWithoutGoal();
                break;
            }

            current.closed = true;
            expanded++;
            if (current.h < best.h) best = current;

            if (goal.reached(current.x, current.y, current.z)) {
                result = buildPath(current, false);
                state = State.FOUND;
                break;
            }
            if (expanded >= maxNodes) {
                finishWithoutGoal();
                break;
            }
            expand(current);
        }
        return state;
    }

    public State getState() {
        return state;
    }

    public Path getPath() {
        return result;
    }

    public int getExpandedNodes() {
        return expanded;
    }

    /**
     * 플레이어가 (x, y, z) 에 발을 두고 설 수 있는지.
     */
    public static boolean isStandable(TerrainView terrain, int x, int y, int z) {
        return standCost(terrain, x, y, z) >= 0.0;
    }

    // 큐에는 더 좋은 경로로 대체된 옛 노드가 남아 있을 수 있어서 걸러낸다.
    private Node pollNext() {
        Node node;
        while ((node = open.poll()) != null) {
            if (!node.closed && nodes.get(BlockPoint.pack(node.x, node.y, node.z)) == node) return node;
        }
        return null;
    }

    private void finishWithoutGoal() {
        if (best != start && best.h < start.h - MIN_PARTIAL_PROGRESS) {
            result = buildPath(best, true);
            state = State.PARTIAL;
        } else {
            state = State.FAILED;
        }
    }

    private void expand(Node current) {
        for (int[] direction : DIRECTIONS) {
            int dx = direction[0];
            int dz = direction[1];
            int nx = current.x + dx;
            int nz = current.z + dz;
            if (Math.abs(nx - start.x) > maxDistance || Math.abs(nz - start.z) > maxDistance) continue;

            boolean diagonal = dx != 0 && dz != 0;
            // 대각선 이동은 양옆이 모두 비어 있을 때만 허용한다 (모서리를 뚫고 지나가지 않도록).
            if (diagonal && !(isColumnClear(current.x + dx, current.y, current.z) && isColumnClear(current.x, current.y, current.z + dz))) {
                continue;
            }
            double baseCost = diagonal ? DIAGONAL_COST : 1.0;

            double cost = standCost(terrain, nx, current.y, nz);
            if (cost >= 0.0) {
                relax(current, nx, current.y, nz, baseCost + cost);
                continue;
            }

            if (!diagonal && isPassable(terrain.classify(current.x, current.y + 2, current.z))) {
                cost = standCost(terrain, nx, current.y + 1, nz);
                if (cost >= 0.0) {
                    relax(current, nx, current.y + 1, nz, baseCost + JUMP_COST + cost);
                    continue;
                }
            }

            if (isColumnClear(nx, current.y, nz)) expandDrop(current, nx, nz, baseCost);
        }
    }

    private void expandDrop(Node current, int nx, int nz, double baseCost) {
        for (int drop = 1; drop <= maxDrop; drop++) {
            int y = current.y - drop;
            double cost = standCost(terrain, nx, y, nz);
            if (cost >= 0.0) {
                relax(current, nx, y, nz, baseCost + drop * DROP_COST_PER_BLOCK + cost);
                return;
            }
            if (!isPassable(terrain.classify(nx, y, nz))) return;
        }
    }

    private void relax(Node parent, int x, int y, int z, double cost) {
        long key = BlockPoint.pack(x, y, z);
        double g = parent.g + cost;
        Node existing = nodes.get(key);
        if (existing != null && (existing.closed || existing.g <= g)) return;

        Node node = new Node(x, y, z, g, goal.heuristic(x, y, z), parent);
        nodes.put(key, node);
        open.add(node);
    }

    private Path buildPath(Node end, boolean partial) {
        List<BlockPoint> points = new ArrayList<>();
        for (Node node = end; node != null && node != start; node = node.parent) {
            points.add(new BlockPoint(node.x, node.y, node.z));
        }
        Collections.reverse(points);
        return new Path(points, partial);
    }

    private boolean isColumnClear(int x, int y, int z) {
        return isPassable(terrain.classify(x, y, z)) && isPassable(terrain.classify(x, y + 1, z));
    }

    private static boolean isPassable(BlockClass blockClass) {
        return blockClass == BlockClass.OPEN || blockClass == BlockClass.WATER;
    }

    // 설 수 없으면 음수, 설 수 있으면 그 칸에 서는 데 드는 추가 비용.
    private static double standCost(TerrainView terrain, int x, int y, int z) {
        BlockClass feet = terrain.classify(x, y, z);
        BlockClass head = terrain.classify(x, y + 1, z);
        BlockClass below = terrain.classify(x, y - 1, z);

        double cost;
        if (feet == BlockClass.OPEN) {
            if (head != BlockClass.OPEN || below != BlockClass.SOLID) return -1.0;
            cost = 0.0;
        } else if (feet == BlockClass.WATER) {
            if (!isPassable(head) || below == BlockClass.DANGER) return -1.0;
            // 머리까지 잠기면 숨을 못 쉬므로 수면보다 더 비싸게 친다.
            cost = head == BlockClass.WATER ? WATER_COST * 2 : WATER_COST;
        } else {
            return -1.0;
        }

        if (hasDangerAround(terrain, x, y, z)) cost += NEAR_DANGER_COST;
        return cost;
    }

    private static boolean hasDangerAround(TerrainView terrain, int x, int y, int z) {
        for (int i = 0; i < 4; i++) {
            int nx = x + DIRECTIONS[i][0];
            int nz = z + DIRECTIONS[i][1];
            if (terrain.classify(nx, y, nz) == BlockClass.DANGER || terrain.classify(nx, y - 1, nz) == BlockClass.DANGER) return true;
        }
        return false;
    }
}
