package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.inventory.StorageItems;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * 집의 상자를 열어서 물건을 넣거나 꺼내는 행동의 공통 부분.
 * 실제 플레이어처럼 다가가서 상자를 열고, 잠깐 뒤에 물건을 옮기고, 닫는다. 끝나면 상자의 내용물을 기억해 둔다.
 */
abstract class ChestAction extends AbstractAction {
    private static final int TIMEOUT = 100;
    private static final double MAX_REACH = 4.9;
    // 상자를 열고 나서 물건을 옮기기까지의 시간. 바로 닫으면 연 것처럼 보이지 않는다.
    private static final int OPEN_TICKS = 12;

    protected final BlockPoint chest;
    private World world;
    private boolean opened;
    private int openedAt;

    protected ChestAction(String name, BlockPoint chest) {
        super(name, TIMEOUT);
        this.chest = chest;
    }

    /**
     * 열린 상자와 물건을 주고받는다. 이 안에서 succeed() 나 fail() 을 불러야 한다.
     */
    protected abstract void transfer(AIPlayer ai, Player player, Inventory chestInventory);

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, chest)) {
            fail("world changed");
            return;
        }
        Block block = Positions.block(world, chest);
        if (!(block.getState(false) instanceof Container container)) {
            // 상자가 부서졌거나 사라졌다. 더는 찾아가지 않도록 잊는다.
            Base home = ai.getWorldModel().homeIn(world.getUID());
            if (home != null) home.removeChest(chest);
            ai.getWorldModel().forgetStorage(world.getUID(), chest);
            fail("chest disappeared");
            return;
        }

        Location center = Positions.center(world, chest);
        if (player.getEyeLocation().distance(center) > MAX_REACH) {
            fail("out of reach");
            return;
        }
        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());

        if (!opened) {
            if (player.openInventory(container.getInventory()) == null) {
                fail("cannot open chest");
                return;
            }
            opened = true;
            openedAt = getElapsed();
            player.swingMainHand();
            return;
        }
        if (getElapsed() - openedAt < OPEN_TICKS) return;

        Inventory inventory = container.getInventory();
        transfer(ai, player, inventory);
        // 넣거나 꺼낸 뒤의 내용물을 기억한다. 다음에 재료가 필요할 때 여기에 있는지부터 떠올린다.
        ai.getWorldModel().rememberContents(world.getUID(), chest, StorageItems.contentsOf(inventory));
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        if (opened) ai.getPlayer().closeInventory();
    }
}
