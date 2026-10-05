package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.function.Predicate;

/**
 * 집 상자에서 필요한 재료를 꺼낸다. 새로 캐러 가기 전에, 이미 모아 둔 것이 있으면 그것부터 쓴다.
 */
public final class WithdrawItemsAction extends ChestAction implements PrimitiveAction {
    private final Predicate<Material> wanted;
    private final int maxAmount;

    /**
     * @param wanted    꺼낼 아이템의 종류
     * @param maxAmount 꺼낼 최대 개수
     */
    public WithdrawItemsAction(BlockPoint chest, Predicate<Material> wanted, int maxAmount) {
        super("WithdrawItems", chest);
        this.wanted = wanted;
        this.maxAmount = maxAmount;
    }

    @Override
    protected void transfer(AIPlayer ai, Player player, Inventory chestInventory) {
        int remaining = maxAmount;
        int taken = 0;
        for (int slot = 0; slot < chestInventory.getSize() && remaining > 0; slot++) {
            ItemStack item = chestInventory.getItem(slot);
            if (item == null || item.isEmpty() || !wanted.test(item.getType())) continue;
            int amount = Math.min(remaining, item.getAmount());
            ItemStack part = item.clone();
            part.setAmount(amount);
            // 가방에 다 들어가지 않으면 들어간 만큼만 상자에서 뺀다.
            int rejected = 0;
            for (ItemStack leftover : player.getInventory().addItem(part).values()) rejected += leftover.getAmount();
            int moved = amount - rejected;
            if (moved <= 0) break;
            if (moved >= item.getAmount()) chestInventory.setItem(slot, null);
            else item.setAmount(item.getAmount() - moved);
            remaining -= moved;
            taken += moved;
        }

        // 무언가를 꺼냈으면 상자에 빈자리가 생긴 것이다.
        if (taken > 0) ai.getWorldModel().markStorageFull(false);
        ai.debug("Took " + taken + " item(s) from the chest at " + chest);
        if (taken == 0) {
            // 기억과 달리 상자에 없었다. 내용물 기억은 ChestAction 이 방금 본 대로 고쳐 둔다.
            fail("nothing to take");
            return;
        }
        ai.getTeam().say(ai, Phrases.fetched(), false);
        succeed();
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
