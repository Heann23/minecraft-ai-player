package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 구조물의 전리품 상자를 열어서 내용물을 챙긴다.
 * 실제 플레이어처럼 상자를 열기 때문에, 그때 전리품이 채워지고 다른 플러그인도 상자가 열린 것을 알 수 있다.
 */
public final class LootChestAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 100;
    private static final double MAX_REACH = 4.9;
    // 상자를 열고 나서 내용물을 꺼내기까지의 시간. 바로 닫으면 연 것처럼 보이지 않는다.
    private static final int OPEN_TICKS = 15;

    private final BlockPoint chest;
    private World world;
    private boolean opened;
    private int openedAt;

    public LootChestAction(BlockPoint chest) {
        super("LootChest", TIMEOUT);
        this.chest = chest;
    }

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
            // 상자를 여는 순간 전리품이 채워진다.
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

        int taken = takeAll(player, container.getInventory());
        ai.getMemory().forget(MemoryType.LOOT_CHEST, world.getUID(), chest);
        ai.getInventory().wearBestArmor();
        ai.debug("Looted " + taken + " item stack(s) from the chest at " + chest);
        ai.getTeam().say(ai, Phrases.lootedChest(taken), true);
        succeed();
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        if (opened) ai.getPlayer().closeInventory();
    }

    // 인벤토리에 들어가는 만큼 꺼낸다. 들어가지 않는 것은 상자에 그대로 둔다.
    private static int takeAll(Player player, Inventory inventory) {
        int taken = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            ItemStack leftover = player.getInventory().addItem(item.clone()).values().stream().findFirst().orElse(null);
            inventory.setItem(slot, leftover);
            if (leftover == null || leftover.getAmount() < item.getAmount()) taken++;
        }
        return taken;
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.INTERACT_BLOCK;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(chest);
    }
}
