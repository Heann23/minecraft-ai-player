package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;

/**
 * 핫바의 특정 칸을 손에 든다.
 */
public final class SelectHotbarSlotAction extends AbstractAction implements PrimitiveAction {
    private final int slot;

    public SelectHotbarSlotAction(int slot) {
        super("SelectHotbarSlot", 5);
        this.slot = slot;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        if (slot < 0 || slot > 8) {
            fail("invalid slot");
            return;
        }
        ai.getPlayer().getInventory().setHeldItemSlot(slot);
        succeed();
    }

    @Override
    protected void onTick(AIPlayer ai) {
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.SELECT_SLOT;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Slot(slot);
    }
}
