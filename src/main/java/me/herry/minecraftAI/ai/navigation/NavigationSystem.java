package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.AIBody;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.perf.TickProfiler;
import me.herry.minecraftAI.ai.perf.WorkBudget;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.config.AIConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * 경로를 찾고 그 경로를 따라 몸체를 걷게 한다.
 * 순간이동은 쓰지 않고, 방향을 돌리고 앞으로 걷고 점프하는 입력만 넣는다.
 */
public final class NavigationSystem {
    public enum State { IDLE, SEARCHING, FOLLOWING, ARRIVED, FAILED }

    // 이보다 높은 곳에서 떨어지면 낙하 피해를 입는다.
    private static final int MAX_DROP = 3;
    private static final int STUCK_CHECK_INTERVAL = 20;
    private static final double STUCK_MOVE_SQ = 1.0;
    // 거미줄 안에서는 걷는 속도가 4분의 1이라, 조금이라도 나아가고 있으면 막힌 것으로 보지 않는다.
    private static final double STUCK_MOVE_IN_WEB_SQ = 0.2 * 0.2;
    // 헤엄쳐 오르거나 떨어지는 것처럼 높이가 이만큼 바뀌었으면 움직인 것이다. 제자리 점프는 착지하면 높이가 같다.
    private static final double STUCK_CLIMB = 1.5;
    private static final double WAYPOINT_REACH_SQ = 0.16;
    // 곧은 평지를 달릴 때는 칸의 한가운데를 밟지 않아도 지나간 것으로 본다. 점프 중에는 칸 위를 날아서 지나간다.
    private static final double RUN_REACH_SQ = 0.7 * 0.7;
    private static final double RUN_REACH_ABOVE = 1.6;
    private static final double OFF_PATH_SQ = 9.0;
    // 부분 경로를 이어 붙일 수 있는 최대 횟수. 목표에 가까워지지 못하면 그 전에 포기한다.
    private static final int MAX_SEGMENTS = 12;
    private static final double MIN_SEGMENT_PROGRESS = 2.0;
    private static final int JUMP_PULSE_TICKS = 6;
    // 허기가 이 값 이하이면 달릴 수 없다.
    private static final int MIN_SPRINT_FOOD = 6;
    // 달리면서 점프하면 더 빨리 가지만 허기가 훨씬 빨리 준다. 배가 넉넉할 때만 하고, 싸울 상대를 쫓을 때는 달릴 수 있는 한 한다.
    private static final int SPRINT_JUMP_MIN_FOOD = 18;
    // 몸이 가는 방향으로 거의 돌아섰을 때만 뛴다. 비스듬히 뛰면 경로에서 벗어난 칸에 내린다.
    private static final float SPRINT_JUMP_YAW = 10.0F;
    private static final int TRAPPED_NODE_LIMIT = 150;
    // 걸어서 갈 수 있는 범위가 너무 좁아서 어디로도 갈 수 없을 때의 실패 원인
    public static final String FAIL_TRAPPED = "trapped";

    // 한 번에 펼치는 노드 수. 이만큼 펼칠 때마다 시간 예산을 확인한다.
    private static final int SEARCH_SLICE = 50;

    private final AIBody body;
    private final AIConfig config;
    private final Consumer<String> debug;
    private final WorkBudget budget;
    private final TickProfiler profiler;
    private final InventorySystem inventory;
    private final DoorOpener doors = new DoorOpener();
    private final WebCutter webs = new WebCutter();

    private State state = State.IDLE;
    private String failReason = "";
    private PathGoal goal;
    private World world;
    private BukkitTerrainView terrain;
    private AStarSearch search;
    private Path path;
    private int index;
    private boolean sprintAllowed = true;
    // 목적지 바로 앞까지 달릴지. 움직이는 상대를 쫓을 때 쓴다. 평소에는 목적지 네 칸 앞에서 걷기 시작해서 지나치지 않게 한다.
    private boolean sprintToEnd;
    // 배가 넉넉하지 않아도 달리며 점프할지. 싸울 상대를 쫓을 때만 쓴다.
    private boolean jumpWhenHungry;
    // 지금 향하는 칸부터가 뛰어도 되는 곧은 평지인지 (칸이 바뀔 때만 다시 확인한다)
    private int runCheckedIndex = -1;
    private boolean runClear;
    // 이번 이동에서 점프를 섞어 달리기 시작했다고 이미 적었는지 (디버그 로그는 이동마다 한 번만 남긴다)
    private boolean runAnnounced;
    // 경로에 없는 낙하를 이미 알렸는지 (떨어지는 동안 틱마다 알리지 않게)
    private boolean fallAnnounced;

    private int repaths;
    private int segments;
    private double bestDistance;
    private Location lastCheck;
    private int indexAtLastCheck;
    private int ticksSinceCheck;
    private int stuckTicks;
    // 지난번에 막혔는지 확인한 뒤로 거미줄 안에 있었던 적이 있는지
    private boolean webbedSinceCheck;
    private int jumpPulse;

    public NavigationSystem(AIBody body, AIConfig config, Consumer<String> debug, WorkBudget budget, TickProfiler profiler,
                            InventorySystem inventory) {
        this.body = body;
        this.config = config;
        this.debug = debug;
        this.budget = budget;
        this.profiler = profiler;
        this.inventory = inventory;
    }

    public void navigateTo(PathGoal goal) {
        this.goal = goal;
        this.sprintToEnd = false;
        this.jumpWhenHungry = false;
        this.runAnnounced = false;
        this.fallAnnounced = false;
        this.repaths = 0;
        this.segments = 0;
        this.stuckTicks = 0;
        this.bestDistance = Double.MAX_VALUE;
        this.failReason = "";
        startSearch();
    }

    public void stop() {
        if (body.isUsable()) doors.finish(body.getPlayer());
        webs.reset();
        state = State.IDLE;
        search = null;
        path = null;
        goal = null;
        // 멈춰 있는 동안 언로드된 월드를 붙잡고 있지 않게 한다.
        world = null;
        terrain = null;
        body.inputMove(0.0F, 0.0F);
        body.inputJump(false);
        body.inputSprint(false);
    }

    public void tick() {
        if (state != State.SEARCHING && state != State.FOLLOWING) return;

        Player player = body.getPlayer();
        if (!player.getWorld().equals(world)) {
            fail("world changed");
            return;
        }

        if (state == State.SEARCHING) tickSearch(player);
        else tickFollow(player);
    }

    public State getState() {
        return state;
    }

    public boolean isBusy() {
        return state == State.SEARCHING || state == State.FOLLOWING;
    }

    public String getFailReason() {
        return failReason;
    }

    public PathGoal getGoal() {
        return goal;
    }

    public void setSprintAllowed(boolean sprintAllowed) {
        this.sprintAllowed = sprintAllowed;
    }

    /**
     * 움직이는 상대를 쫓는 이동으로 표시한다. 목적지 바로 앞까지 달린다. navigateTo 를 부를 때마다 꺼지므로 그 뒤에 부른다.
     *
     * @param urgent 싸울 상대를 쫓는 것처럼 급한지. 급하면 배가 넉넉하지 않아도 점프를 섞어 달린다.
     */
    public void setChasing(boolean urgent) {
        this.sprintToEnd = true;
        this.jumpWhenHungry = urgent;
    }

    private void startSearch() {
        Player player = body.getPlayer();
        world = player.getWorld();
        terrain = new BukkitTerrainView(world);
        runCheckedIndex = -1;
        // 시작점은 설 수 있는 칸인지와 상관없이 실제로 서 있는 칸이어야 한다.
        // 다른 칸에서 시작하면 실제로는 닿지 않았는데 도착한 것으로 판정될 수 있다.
        BlockPoint start = Positions.feet(player.getLocation());

        search = new AStarSearch(terrain, start, goal, config.maxPathNodes, config.maxPathDistance, MAX_DROP);
        path = null;
        state = State.SEARCHING;
        body.inputMove(0.0F, 0.0F);
        body.inputSprint(false);
        debug.accept("Planning path...");
    }

    private void tickSearch(Player player) {
        // 계산하는 동안 물에 가라앉지 않도록 떠 있는다.
        body.inputJump(player.isInWater());

        long started = profiler.begin();
        AStarSearch.State result = AStarSearch.State.RUNNING;
        int remaining = config.nodesPerTick;
        // 조금씩 나눠 펼치면서, 이번 서버 틱의 시간 예산을 다 썼으면 나머지는 다음 틱에 이어서 한다.
        while (remaining > 0) {
            int slice = Math.min(SEARCH_SLICE, remaining);
            result = search.advance(slice);
            remaining -= slice;
            if (result != AStarSearch.State.RUNNING || budget.isExhausted()) break;
        }
        profiler.end(TickProfiler.Section.PATHFINDING, started);
        if (result == AStarSearch.State.RUNNING) return;

        if (result == AStarSearch.State.FAILED) {
            // 갈 수 있는 칸이 몇 개 없는데 탐색이 끝났다면 좁은 곳(구덩이, 나무 꼭대기)에 갇힌 것이다.
            fail(search.getExpandedNodes() < TRAPPED_NODE_LIMIT ? FAIL_TRAPPED : "no path");
            return;
        }

        path = search.getPath();
        search = null;
        index = 0;
        segments++;
        lastCheck = player.getLocation();
        indexAtLastCheck = 0;
        ticksSinceCheck = 0;
        state = State.FOLLOWING;
        debug.accept("Path found: " + path.size() + " steps" + (path.partial() ? " (partial)" : ""));
    }

    private void tickFollow(Player player) {
        Location location = player.getLocation();
        BlockPoint feet = Positions.feet(location);
        // 경로는 한 번에 MAX_DROP 칸까지만 내려간다. 그보다 깊이 떨어지고 있으면 길을 벗어나 낭떠러지로 빠진 것이다.
        boolean falling = !body.isGrounded() && !player.isInWater() && player.getFallDistance() > MAX_DROP + 0.5F;
        if (!falling) {
            fallAnnounced = false;
        } else if (!fallAnnounced) {
            fallAnnounced = true;
            // 어디서 어디로 가다가 떨어졌는지 남긴다. 절벽에서 떨어져 죽는 원인을 찾는 단서다.
            debug.accept("Falling off the path at " + feet + (index < path.size() ? ", was heading to " + path.get(index) : "")
                    + " (step " + index + " of " + path.size() + (isRunClear() ? ", running" : "") + ")");
        }
        // 떨어지면서 목적지 옆을 스쳐 지나가는 것은 도착이 아니다. 그대로 성공으로 끝내면 다음 행동(블록 캐기 등)이 허공에서 실패한다.
        if (!falling && goal.reached(feet.x(), feet.y(), feet.z())) {
            arrive();
            return;
        }
        if (index >= path.size()) {
            onPathEnd(feet);
            return;
        }

        BlockPoint waypoint = path.get(index);
        doors.tick(player, waypoint);
        // 거미줄에 걸려 있거나 다음 칸이 거미줄이면 멈춰 서서 베어 낸다. 베는 동안 서 있는 것은 막힌 것으로 세지 않는다.
        if (webs.tick(player, body, inventory, waypoint, stuckTicks > 0, debug)) {
            lastCheck = location;
            indexAtLastCheck = index;
            ticksSinceCheck = 0;
            return;
        }
        double dx = waypoint.x() + 0.5 - location.getX();
        double dz = waypoint.z() + 0.5 - location.getZ();
        double dy = waypoint.y() - location.getY();
        double horizontalSq = dx * dx + dz * dz;

        boolean running = isRunClear();
        // 점프해서 떠 있는 동안에도 아래로 지나가는 칸을 지나간 것으로 센다. 세지 않으면 지나친 칸으로 되돌아가려고 돌아선다.
        // 물에 떠 있을 때는 해당하지 않는다. 물속의 칸은 잠겨서 지나가야 한다.
        boolean airborne = !body.isGrounded() && !player.isInWater();
        double above = running || airborne ? RUN_REACH_ABOVE : 1.2;
        if (horizontalSq < (running ? RUN_REACH_SQ : WAYPOINT_REACH_SQ) && dy > -above && dy < 0.6) {
            index++;
            return;
        }
        if (horizontalSq > OFF_PATH_SQ || dy > 2.5 || dy < -(MAX_DROP + 1.5)) {
            repath("off path");
            return;
        }

        steer(player, dx, dy, dz, horizontalSq);
        if (WebCutter.isInWeb(player)) webbedSinceCheck = true;
        checkStuck(location);
    }

    private void steer(Player player, double dx, double dy, double dz, double horizontalSq) {
        float yaw = Positions.yawTo(dx, dz);
        body.inputLook(yaw, 0.0F);
        float yawDiff = Math.abs(Positions.angleDifference(player.getLocation().getYaw(), yaw));
        // 방향이 많이 틀어져 있으면 제자리에서 먼저 돌아선다. 그대로 걸으면 낭떠러지로 빠질 수 있다.
        boolean facing = yawDiff < 50.0F;
        body.inputMove(facing ? 1.0F : 0.0F, 0.0F);

        boolean inWater = player.isInWater();
        boolean jump;
        if (inWater) {
            jump = dy > -0.6;
        } else if (body.isGrounded() && dy > 0.6 && horizontalSq < 4.0) {
            jump = true;
        } else {
            jump = body.isGrounded() && facing && body.isBlockedHorizontally();
        }
        if (jumpPulse > 0) {
            jumpPulse--;
            jump = true;
        }

        int food = player.getFoodLevel();
        boolean sprint = sprintAllowed && !inWater && yawDiff < 30.0F && (path.size() - index > 4 || sprintToEnd) && food > MIN_SPRINT_FOOD;
        body.inputSprint(sprint);
        // 곧은 평지를 달릴 때는 점프를 섞어서 더 빨리 간다.
        boolean fed = food >= SPRINT_JUMP_MIN_FOOD || jumpWhenHungry;
        if (sprint && fed && body.isGrounded() && yawDiff < SPRINT_JUMP_YAW && isRunClear()) {
            jump = true;
            if (!runAnnounced) {
                runAnnounced = true;
                debug.accept("Running with jumps");
            }
        }
        body.inputJump(jump);
    }

    // 지금 향하는 칸부터 몇 칸이, 달리며 점프해도 되는 곧은 평지인지.
    private boolean isRunClear() {
        if (runCheckedIndex != index) {
            runCheckedIndex = index;
            runClear = terrain != null && RunAhead.isClear(terrain, path, index);
        }
        return runClear;
    }

    // 일정 시간마다 위치를 비교해서 제자리에 묶여 있는지 확인하고, 점프와 경로 재계산을 번갈아 시도한다.
    private void checkStuck(Location location) {
        if (++ticksSinceCheck < STUCK_CHECK_INTERVAL) return;
        ticksSinceCheck = 0;

        // 경로의 다음 칸으로 넘어갔거나 수평으로 한 칸 이상 움직였을 때만 나아간 것으로 본다.
        // 제자리에서 뛰거나 장애물에 밀려 조금 흔들리는 것까지 이동으로 치면, 막혀 있는데도 끝없이 점프만 하게 된다.
        double dx = location.getX() - lastCheck.getX();
        double dz = location.getZ() - lastCheck.getZ();
        // 거미줄에서 막 빠져나온 직후에도, 그 구간에서 느리게 움직인 것을 막힌 것으로 세지 않는다.
        double needed = webbedSinceCheck ? STUCK_MOVE_IN_WEB_SQ : STUCK_MOVE_SQ;
        webbedSinceCheck = false;
        boolean moved = index > indexAtLastCheck || dx * dx + dz * dz >= needed
                || Math.abs(location.getY() - lastCheck.getY()) >= STUCK_CLIMB;
        lastCheck = location;
        indexAtLastCheck = index;
        if (moved) {
            stuckTicks = 0;
            return;
        }

        stuckTicks += STUCK_CHECK_INTERVAL;
        if (stuckTicks >= config.stuckTimeout) {
            fail("stuck");
        } else if ((stuckTicks / STUCK_CHECK_INTERVAL) % 2 == 1) {
            debug.accept("Stuck: jumping");
            jumpPulse = JUMP_PULSE_TICKS;
        } else {
            repath("stuck");
        }
    }

    private void onPathEnd(BlockPoint feet) {
        if (!path.partial()) {
            // 경로의 마지막 칸까지 왔는데 도착 조건이 아직 안 맞으면 (밀려났거나 떨어졌으면) 다시 찾는다.
            repath("missed goal");
            return;
        }

        // 부분 경로의 끝. 목표에 실제로 가까워졌을 때만 다음 구간을 이어서 찾는다.
        double distance = goal.distance(feet.x(), feet.y(), feet.z());
        if (distance < bestDistance - MIN_SEGMENT_PROGRESS && segments < MAX_SEGMENTS) {
            bestDistance = distance;
            startSearch();
        } else {
            fail("unreachable");
        }
    }

    // 재계산 횟수를 제한해서 경로 재계산이 무한히 반복되지 않게 한다.
    private void repath(String reason) {
        if (++repaths > config.maxRepaths) {
            fail(reason);
            return;
        }
        debug.accept("Replanning path (" + reason + ")");
        startSearch();
    }

    private void arrive() {
        state = State.ARRIVED;
        search = null;
        body.inputMove(0.0F, 0.0F);
        body.inputJump(false);
        body.inputSprint(false);
    }

    private void fail(String reason) {
        state = State.FAILED;
        failReason = reason;
        search = null;
        path = null;
        body.inputMove(0.0F, 0.0F);
        body.inputJump(false);
        body.inputSprint(false);
    }
}
