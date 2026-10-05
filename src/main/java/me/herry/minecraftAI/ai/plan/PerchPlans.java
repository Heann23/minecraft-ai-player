package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.PerchRules;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;

import java.util.List;

/**
 * 나무 위에서 땅으로 내려오는 계획. 나무 꼭대기에서 스폰됐거나 나무를 베다가 잎 위에 남았을 때 쓴다.
 */
public final class PerchPlans {
    private PerchPlans() {
    }

    /**
     * 나뭇잎이나 줄기 위에 서 있으면 발밑 블록을 캐서 한 단 내려온다. 걸어서 내려갈 길이 있는지는 부르는 쪽이 먼저 본다.
     * 나무 위가 아니거나, 떨어지면 크게 다칠 높이면 빈 목록.
     */
    static List<Action> climbDown(AIPlayer ai) {
        if (!ai.getBody().isGrounded()) return List.of();
        World world = ai.getPlayer().getWorld();
        BlockPoint feet = ai.getPosition();
        BlockPoint below = feet.offset(0, -1, 0);
        if (below.y() < world.getMinHeight() || !Positions.isLoaded(world, below)) return List.of();
        if (!isTreePart(Positions.block(world, below).getType())) return List.of();

        PerchRules.Landing landing = PerchRules.landingBelow(new BukkitTerrainView(world), feet);
        if (landing == null || !PerchRules.isAcceptable(landing, ai.getPlayer().getHealth())) return List.of();
        ai.debug("Climbing down from the tree at " + feet + ", dropping " + landing.drop());
        return List.of(new BreakBlockAction(below));
    }

    private static boolean isTreePart(Material material) {
        return Tag.LEAVES.isTagged(material) || Tag.LOGS.isTagged(material);
    }
}
