package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.ToolChoice;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** Selects exactly the observed stack rather than silently substituting another tool. */
public final class SlotEquipAction extends AbstractAction implements PrimitiveAction {
    private final ToolChoice choice;

    public SlotEquipAction(ToolChoice choice) {
        super("EquipSlot", 1);
        this.choice = Objects.requireNonNull(choice, "choice");
    }

    @Override
    protected void onStart(AIPlayer ai) {
        if (!ai.getBody().isUsable()) {
            fail("body unavailable");
            return;
        }
        if (choice.slot() < 0 || choice.slot() >= 36) {
            fail("invalid inventory slot");
            return;
        }
        ItemStack selected = ai.getPlayer().getInventory().getItem(choice.slot());
        if (!ToolChoiceItems.matches(selected, choice)) {
            fail("tool changed");
            return;
        }
        if (!ai.getInventory().equipSlot(choice.slot())
                || !ToolChoiceItems.matches(ai.getPlayer().getInventory().getItemInMainHand(), choice)) {
            fail("tool unavailable");
            return;
        }
        succeed();
    }

    @Override
    protected void onTick(AIPlayer ai) {
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.EQUIP_ITEM;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Slot(choice.slot());
    }
}
