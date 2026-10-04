package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 베기 시작한 나무를 끝까지 베는 계획. 아래쪽 원목부터 베고, 손이 닿지 않으면 기둥 자리에 블록을 쌓고 올라간다.
 * 다 베면 쌓았던 블록을 하나씩 캐면서 내려온다.
 */
final class TreePlans {
    // 눈에서 원목 중심까지 이 거리 안이면 벨 수 있다. BreakBlockAction 의 최대 거리보다 조금 짧게 잡는다.
    private static final double BREAK_REACH = 4.5;
    // 땅에 서서 벨 수 있는 높이 (발 기준)
    private static final int GROUND_REACH = 5;
    // 기둥 위에서 이보다 높이 있는 원목은 한 칸 더 쌓아서 다가간다.
    private static final int CLIMB_GAP = 3;
    private static final double DROP_RADIUS = 6.0;
    private static final int MAX_SKIPS = 8;
    private static final int LANDING_WAIT_TICKS = 5;

    private TreePlans() {
    }

    /**
     * 베던 나무의 다음 원목을 벤다. 남은 원목이 없거나 더는 벨 수 없으면 기둥에서 내려오고, 그것도 없으면 빈 목록.
     */
    static List<Action> continueTree(AIPlayer ai, TreeJob job) {
        // 발판에서 한 칸 내려오는 중이면 땅에 닿을 때까지 기다린다.
        if (!ai.getBody().isGrounded()) return List.of(new WaitAction(LANDING_WAIT_TICKS));
        Player player = ai.getPlayer();
        World world = player.getWorld();
        BlockPoint feet = ai.getPosition();
        boolean onPillar = job.pillarUnder(feet) != null;

        for (int skips = 0; skips < MAX_SKIPS && job.hasLogs(); skips++) {
            BlockPoint log = job.lowest(feet);
            if (log == null) break;
            ai.getTeam().claim(ai, log);

            if (Positions.center(world, log).distance(player.getEyeLocation()) <= BREAK_REACH) {
                List<Action> actions = new ArrayList<>();
                actions.add(new BreakBlockAction(log));
                // 기둥 위에서는 아이템이 땅으로 떨어지므로 내려온 뒤에 줍는다.
                if (!onPillar) actions.add(new PickupItemAction(DROP_RADIUS, false));
                return actions;
            }

            BlockPoint stand = job.trunkStand(world);
            boolean onColumn = feet.x() == stand.x() && feet.z() == stand.z();
            if (onColumn) {
                if (log.y() - feet.y() >= CLIMB_GAP) {
                    List<Action> up = TerrainPlans.pillarUp(ai, job::addPillar);
                    if (!up.isEmpty()) return up;
                    ai.debug("Cannot climb to the log at " + log + " (no blocks?), leaving it");
                }
                // 기둥에서 옆으로 멀리 뻗은 가지는 손이 닿지 않는다.
                job.dropLog(log);
                continue;
            }
            if (onPillar) {
                job.dropLog(log);
                continue;
            }

            if (log.y() - stand.y() <= GROUND_REACH) {
                return List.of(
                        new MoveToAction(PathGoal.reach(log, BREAK_REACH - 0.5), false),
                        new BreakBlockAction(log),
                        new PickupItemAction(DROP_RADIUS, false)
                );
            }
            // 높은 원목은 나무 기둥이 있던 자리로 가서 블록을 쌓고 올라간다.
            ai.debug("Log at " + log + " is high up, going to the trunk at " + stand + " to climb");
            return List.of(new MoveToAction(PathGoal.arrive(stand, 0.3), false));
        }
        return descend(ai, job);
    }

    // 쌓아 올린 블록 위에 서 있으면 발밑 블록을 캐서 한 칸 내려온다. 아니면 빈 목록.
    static List<Action> descend(AIPlayer ai, TreeJob job) {
        BlockPoint below = job.pillarUnder(ai.getPosition());
        if (below == null) return List.of();
        ai.debug("Climbing down the pillar at " + below);
        return List.of(new BreakBlockAction(below));
    }
}
