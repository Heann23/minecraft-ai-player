package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import net.minecraft.world.inventory.ContainerInput;
import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftPlayer;

/** Discards the exact selected storage stack through the player's normal inventory throw input. */
public final class DropExactAction extends AbstractAction implements PrimitiveAction {
    private final PrimitiveCommand.DropExact command;

    public DropExactAction(PrimitiveCommand.DropExact command) {
        super("DropExact", 20);
        this.command = command;
    }

    public static String unavailableReason(AIPlayer ai, PrimitiveCommand.DropExact drop) {
        if (!drop.world().equals(ai.getWorldId())) return "drop world changed";
        if (!ai.getBody().isUsable()) return "body unavailable";
        var player = ai.getPlayer();
        var handle = ((CraftPlayer) player).getHandle();
        if (handle.containerMenu != handle.inventoryMenu || !handle.inventoryMenu.getCarried().isEmpty())
            return "existing menu or cursor is occupied";
        var stack = player.getInventory().getItem(drop.slot());
        if (stack == null || stack.isEmpty()) return "selected drop slot is empty";
        Material expected = Material.getMaterial(drop.material());
        return expected != null && stack.getType() == expected ? "" : "selected drop slot item changed";
    }

    @Override protected void onStart(AIPlayer ai) {
        String reason = unavailableReason(ai, command);
        if (!reason.isEmpty()) { fail(reason); return; }
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
    }

    @Override protected void onTick(AIPlayer ai) {
        String reason = unavailableReason(ai, command);
        if (!reason.isEmpty()) { fail(reason); return; }
        var handle = ((CraftPlayer) ai.getPlayer()).getHandle();
        var menu = handle.inventoryMenu;
        var slot = menu.slots.stream()
                .filter(s -> s.container == handle.getInventory() && s.getContainerSlot() == command.slot())
                .findFirst().orElse(null);
        if (slot == null || slot.getItem().isEmpty()) {
            fail("selected drop slot unavailable");
            return;
        }
        // button=1 with ContainerInput.THROW drops the full stack in that slot via normal server logic.
        menu.clicked(slot.index, 1, ContainerInput.THROW, handle);
        menu.broadcastChanges();
        if (slot.getItem().isEmpty()) {
            ai.debug("Dropped exact stack from slot " + command.slot() + " (" + command.material() + ")");
            succeed();
        } else {
            fail("drop denied");
        }
    }

    @Override public PrimitiveType getPrimitiveType() {
        return PrimitiveType.DROP_ITEM;
    }
}
