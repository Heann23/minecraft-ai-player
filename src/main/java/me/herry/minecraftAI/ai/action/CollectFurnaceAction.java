package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.crafting.FurnaceJob;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;

/**
 * 넣어 두었던 화로에서 결과물과 남은 재료, 남은 연료를 모두 꺼낸다.
 */
public final class CollectFurnaceAction extends AbstractAction implements PrimitiveAction {
    private static final double REACH = 4.5;
    private static final int TIMEOUT = 40;

    private final BlockPoint furnacePos;

    public CollectFurnaceAction(BlockPoint furnacePos) {
        super("CollectFurnace", TIMEOUT);
        this.furnacePos = furnacePos;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        Player player = ai.getPlayer();
        FurnaceInventory furnace = Furnaces.inventory(player.getWorld(), furnacePos);
        if (furnace == null) {
            forgetJob(ai);
            fail("furnace disappeared");
            return;
        }
        if (!NearbyBlocks.canTouch(player, furnacePos, REACH)) {
            fail("furnace out of reach");
            return;
        }

        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
        ai.getBody().lookAt(furnacePos.x() + 0.5, furnacePos.y() + 0.5, furnacePos.z() + 0.5);
        ItemStack result = Furnaces.collect(player, furnace);
        forgetJob(ai);
        player.swingMainHand();
        ai.debug(result == null ? "Collected nothing from the furnace at " + furnacePos
                : "Collected " + result.getAmount() + " " + result.getType() + " from the furnace at " + furnacePos);
        succeed();
    }

    @Override
    protected void onTick(AIPlayer ai) {
    }

    private void forgetJob(AIPlayer ai) {
        FurnaceJob job = ai.getFurnaceJob();
        if (job != null && job.isAt(ai.getWorldId(), furnacePos)) ai.setFurnaceJob(null);
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.INTERACT_BLOCK;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(furnacePos);
    }
}
