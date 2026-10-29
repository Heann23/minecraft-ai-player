package me.herry.minecraftAI.ai.learned;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.AbstractAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;

/** One selected slot operation in the existing Plan lane; never searches for better gear. */
final class EquipExactAction extends AbstractAction implements PrimitiveAction {
    private final ExactEquipment.Choice choice;
    EquipExactAction(ExactEquipment.Choice choice) { super("EquipExact", 1); this.choice = choice; }
    @Override protected void onStart(AIPlayer ai) {
        if (!ai.getBody().isUsable() || !ExactEquipment.apply(ai.getPlayer(), choice)) { fail("equipment slots changed or vanilla transfer denied"); return; }
        succeed();
    }
    @Override protected void onTick(AIPlayer ai) {}
    @Override public PrimitiveType getPrimitiveType() { return PrimitiveType.EQUIP_ITEM; }
    @Override public PrimitiveTarget getTarget() { return new PrimitiveTarget.Slot(choice.source()); }
}
