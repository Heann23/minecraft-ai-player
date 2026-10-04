package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.inventory.StorageItems;
import me.herry.minecraftAI.ai.inventory.StoragePolicy;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;

/**
 * 당장 쓰지 않는 것을 집 상자에 넣는다. 무엇을 넣을지는 StoragePolicy 가 정한다.
 */
public final class DepositItemsAction extends ChestAction {
    private final StoragePolicy.Context context;

    public DepositItemsAction(BlockPoint chest, StoragePolicy.Context context) {
        super("DepositItems", chest);
        this.context = context;
    }

    @Override
    protected void transfer(AIPlayer ai, Player player, Inventory chestInventory) {
        PlayerInventory inventory = player.getInventory();
        Map<Integer, Integer> deposits = StoragePolicy.deposits(StorageItems.stacksOf(inventory), context);
        int moved = 0;
        for (Map.Entry<Integer, Integer> entry : deposits.entrySet()) {
            ItemStack item = inventory.getItem(entry.getKey());
            if (item == null || item.isEmpty()) continue;
            int amount = Math.min(entry.getValue(), item.getAmount());
            ItemStack part = item.clone();
            part.setAmount(amount);
            // 상자에 다 들어가지 않으면 들어간 만큼만 가방에서 뺀다.
            int rejected = 0;
            for (ItemStack leftover : chestInventory.addItem(part).values()) rejected += leftover.getAmount();
            int stored = amount - rejected;
            if (stored <= 0) continue;
            if (stored >= item.getAmount()) inventory.setItem(entry.getKey(), null);
            else item.setAmount(item.getAmount() - stored);
            moved++;
        }

        if (moved == 0 && !deposits.isEmpty()) {
            // 상자가 가득 찼다. 한동안은 넣으러 오지 않는다.
            ai.getWorldModel().markStorageFull(true);
            fail("chest is full");
            return;
        }
        ai.debug("Stored " + moved + " stack(s) in the chest at " + chest);
        if (moved > 0) ai.getTeam().say(ai, Phrases.stored(moved), false);
        succeed();
    }
}
