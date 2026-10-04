package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

/**
 * 배고픈 동료에게 다가가서 가진 음식의 절반을 던져 준다.
 */
public final class GiveFoodAction extends AbstractAction {
    private static final int TIMEOUT = 900;
    private static final double GIVE_RANGE = 3.0;
    private static final int STORAGE_SIZE = 36;
    // 던진 아이템을 자기가 도로 줍지 않도록 잠깐 주울 수 없게 한다.
    private static final int PICKUP_DELAY = 30;

    private final AIPlayer receiver;
    private final EntityChaser chaser = new EntityChaser();

    public GiveFoodAction(AIPlayer receiver) {
        super("GiveFood", TIMEOUT);
        this.receiver = receiver;
    }

    @Override
    protected void onStart(AIPlayer ai) {
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!receiver.getBody().isUsable() || !receiver.getPlayer().getWorld().equals(player.getWorld())) {
            fail("receiver is gone");
            return;
        }

        Player target = receiver.getPlayer();
        EntityChaser.Result result = chaser.tick(ai, target, GIVE_RANGE);
        if (result == EntityChaser.Result.UNREACHABLE) {
            fail("unreachable");
            return;
        }
        if (result != EntityChaser.Result.IN_RANGE) return;

        ai.getBody().inputMove(0.0F, 0.0F);
        int given = throwFood(player, target);
        if (given <= 0) {
            fail("no food to give");
            return;
        }
        ai.debug("Gave " + given + " food item(s) to " + receiver.getName());
        ai.getTeam().say(ai, Phrases.hereIsFood(receiver.getName()), true);
        succeed();
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getNavigation().stop();
        // 성공하든 실패하든 약속은 여기서 끝난다. 실패했다면 상대가 다시 청할 수 있다.
        ai.getTeam().finishFoodRequest(ai);
    }

    // 가진 음식의 절반(적어도 하나)을 상대 쪽으로 던진다. 던진 개수를 돌려준다.
    private static int throwFood(Player giver, Player target) {
        PlayerInventory inventory = giver.getInventory();
        int total = 0;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty() && InventorySystem.isSafeFood(item.getType())) total += item.getAmount();
        }
        int toGive = total / 2;
        if (toGive <= 0) return 0;

        Location from = giver.getEyeLocation();
        Vector direction = Positions.sameWorld(from, target.getLocation())
                ? target.getEyeLocation().toVector().subtract(from.toVector()).normalize().multiply(0.3)
                : new Vector(0, 0, 0);
        int remaining = toGive;
        for (int slot = 0; slot < STORAGE_SIZE && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || !InventorySystem.isSafeFood(item.getType())) continue;
            int amount = Math.min(item.getAmount(), remaining);
            ItemStack thrown = item.clone();
            thrown.setAmount(amount);
            Item entity = giver.getWorld().dropItem(from, thrown);
            entity.setVelocity(direction);
            entity.setPickupDelay(PICKUP_DELAY);
            // 던진 사람은 줍지 못하고 받는 사람만 줍게 한다.
            entity.setOwner(target.getUniqueId());

            if (amount >= item.getAmount()) inventory.setItem(slot, null);
            else item.setAmount(item.getAmount() - amount);
            remaining -= amount;
        }
        giver.swingMainHand();
        return toGive - remaining;
    }
}
