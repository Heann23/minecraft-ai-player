package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.ShaftAccess;
import me.herry.minecraftAI.ai.team.ShaftRegistry;
import me.herry.minecraftAI.ai.util.BlockPoint;

/**
 * 굴을 한 칸 파고 들어선 자리를 동료와 함께 쓰는 굴 목록에 기록한다. 바로 끝나는 행동이다.
 * 실제로 그 칸까지 들어간 뒤에만 기록되도록 이동 행동 뒤에 둔다.
 */
public final class MarkShaftAction extends AbstractAction {
    private final BlockPoint point;

    public MarkShaftAction(BlockPoint point) {
        super("MarkShaft", 1);
        this.point = point;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ShaftRegistry shafts = ai.getTeam().getShafts();
        // 굴의 끝에서 몇 칸 걸어간 자리에서 다시 파기 시작했으면, 그 사이를 잇는 칸부터 적는다.
        // 적어 두지 않으면 굴이 그 사이에서 끊긴 것처럼 기록되고, 그 바닥이 발판으로 보호받지 못한다.
        ShaftRegistry.Shaft latest = shafts.latestOf(ai.getName(), ai.getWorldId());
        BlockPoint tail = latest == null ? null : latest.end();
        for (BlockPoint cell : ShaftAccess.link(new BukkitTerrainView(ai.getPlayer().getWorld()), tail, point)) {
            shafts.record(ai.getName(), ai.getWorldId(), cell);
        }
        shafts.record(ai.getName(), ai.getWorldId(), point);
        succeed();
    }

    @Override
    protected void onTick(AIPlayer ai) {
    }
}
