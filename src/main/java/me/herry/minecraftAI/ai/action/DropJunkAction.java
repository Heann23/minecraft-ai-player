package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.inventory.JunkPolicy;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * 쓰지 않는 아이템을 앞에 던져 버려서 가방에 빈칸을 만든다. 한 묶음씩 사람처럼 시간을 두고 버린다.
 * 버린 아이템은 다시 줍지 않도록 오랫동안 주울 수 없게 해 둔다 (그대로 두면 5분 뒤에 사라진다).
 */
public final class DropJunkAction extends AbstractAction {
    // 버린 아이템을 주울 수 없는 시간. 아이템이 사라지는 시간(5분)과 같다.
    public static final int JUNK_PICKUP_DELAY = 6000;
    // 블록을 캐서 떨어진 아이템은 주울 수 없는 시간이 몇 틱뿐이다. 이보다 길면 일부러 버린 것이다.
    private static final int DISCARDED_THRESHOLD = 200;
    private static final int TIMEOUT = 400;
    private static final int DROP_INTERVAL = 4;
    private static final double THROW_SPEED = 0.3;

    private int dropped;

    public DropJunkAction() {
        super("DropJunk", TIMEOUT);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        if (getElapsed() % DROP_INTERVAL != 0) return;
        Player player = ai.getPlayer();
        PlayerInventory inventory = player.getInventory();
        List<Integer> junk = JunkPolicy.junkSlots(inventory);
        if (junk.isEmpty()) {
            ai.debug("Dropped " + dropped + " stack(s) of unneeded items");
            succeed();
            return;
        }

        int slot = junk.getFirst();
        ItemStack stack = inventory.getItem(slot);
        inventory.setItem(slot, null);
        if (stack == null || stack.isEmpty()) return;
        throwAway(player, stack);
        dropped++;
    }

    private static void throwAway(Player player, ItemStack stack) {
        Location eye = player.getEyeLocation();
        Vector facing = eye.getDirection().setY(0.0);
        if (facing.lengthSquared() < 1.0E-4) facing = new Vector(0, 0, 1);
        Vector direction = facing.normalize().multiply(THROW_SPEED).setY(0.1);
        player.swingMainHand();
        player.getWorld().dropItem(eye.clone().subtract(0.0, 0.3, 0.0), stack, (Item item) -> {
            item.setPickupDelay(JUNK_PICKUP_DELAY);
            item.setVelocity(direction);
        });
    }

    // 버린 쓰레기인지. AI 들은 이런 아이템을 주우러 가지 않는다.
    public static boolean isDiscarded(Item item) {
        return item.getPickupDelay() > DISCARDED_THRESHOLD;
    }
}
