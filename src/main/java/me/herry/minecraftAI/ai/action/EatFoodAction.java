package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 인벤토리의 음식을 손에 들고 먹는다. 먹는 데 걸리는 시간과 효과는 실제 플레이어와 같다.
 */
public final class EatFoodAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 80;
    private static final int MAX_FOOD_LEVEL = 20;

    private int foodBefore;
    private boolean started;

    public EatFoodAction() {
        super("EatFood", TIMEOUT);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (player.getFoodLevel() >= MAX_FOOD_LEVEL) {
            fail("not hungry");
            return;
        }
        int slot = ai.getInventory().bestFoodSlot();
        if (slot < 0) {
            fail("no food");
            return;
        }

        ai.getInventory().equipSlot(slot);
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
        foodBefore = player.getFoodLevel();
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        ai.getBody().inputJump(player.isInWater());

        if (!started) {
            player.startUsingItem(EquipmentSlot.HAND);
            started = true;
            return;
        }
        if (player.getFoodLevel() > foodBefore) {
            succeed();
            return;
        }
        // 공격을 받는 등의 이유로 먹는 동작이 끊기면 실패로 처리해서 다시 판단하게 한다.
        if (!player.isHandRaised()) fail("eating interrupted");
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getPlayer().clearActiveItem();
        ai.getBody().inputJump(false);
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.USE_ITEM;
    }
}
