package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.FillBucketAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.List;

/**
 * 양동이를 쓰는 목표의 행동 계획. 지금은 물을 떠서 물 양동이를 갖추는 것까지 한다.
 */
public final class BucketPlans {
    // 눈높이에서 물까지 이 거리 안으로 다가가서 뜬다. 서버가 허용하는 거리(4.5칸)보다 조금 짧게 잡는다.
    private static final double FILL_REACH = 3.5;

    private BucketPlans() {
    }

    // 아는 물이 있으면 가서 뜬다. 없으면 지상에서 찾는다. 강과 호수, 바다는 지상에 흔하다.
    static List<Action> fillBucket(AIPlayer ai) {
        BlockPoint source = ResourceLocator.locate(ai, MemoryType.WATER_SOURCE, GatherPlans.ORE_WALK_RANGE);
        if (source != null) {
            ai.debug("Target WATER_SOURCE found at " + source);
            return List.of(new MoveToAction(PathGoal.reach(source, FILL_REACH), true), new FillBucketAction(source));
        }
        if (TerrainPlans.needsToClimb(ai)) {
            List<Action> climb = TerrainPlans.climbOut(ai);
            if (!climb.isEmpty()) return climb;
        }
        return GatherPlans.explore(ai);
    }
}
