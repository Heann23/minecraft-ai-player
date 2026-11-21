package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

/** Raises an already-held main-hand or off-hand shield via the normal server item-use path and releases cleanly. */
public final class ShieldBlockAction extends AbstractAction implements PrimitiveAction {
    private final PrimitiveCommand.ShieldBlock command;
    private boolean blocking;

    public ShieldBlockAction(PrimitiveCommand.ShieldBlock command) {
        super("ShieldBlock", command.holdTicks() + 10);
        this.command = command;
    }

    public static String unavailableReason(AIPlayer ai, PrimitiveCommand.ShieldBlock shield) {
        if (!shield.world().equals(ai.getWorldId())) return "shield world changed";
        if (!ai.getBody().isUsable()) return "body unavailable";
        var player = ai.getPlayer();
        if (player.hasActiveItem()) return "already using an item";
        var item = shield.offHand() ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (item.getType() != Material.SHIELD) return "no held shield in selected hand";
        if (player.getCooldown(Material.SHIELD) > 0) return "shield is on cooldown";
        return "";
    }

    @Override protected void onStart(AIPlayer ai) {
        String reason = unavailableReason(ai, command);
        if (!reason.isEmpty()) { fail(reason); return; }
        ai.getBody().inputSprint(false);
        boolean started = command.offHand() ? ai.getBody().useOffHandItem() : ai.getBody().useItem();
        blocking = started && ownsBlock(ai);
        if (!blocking) fail("vanilla shield block denied");
    }

    @Override protected void onTick(AIPlayer ai) {
        if (!command.world().equals(ai.getWorldId()) || !ai.getBody().isUsable() || !ownsBlock(ai)) {
            fail("shield block interrupted");
            return;
        }
        if (getElapsed() >= command.holdTicks()) {
            ai.getPlayer().clearActiveItem();
            blocking = false;
            succeed();
        }
    }

    private boolean ownsBlock(AIPlayer ai) {
        var player = ai.getPlayer();
        EquipmentSlot expectedHand = command.offHand() ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        return player.hasActiveItem() && player.getActiveItemHand() == expectedHand
                && player.getActiveItem().getType() == Material.SHIELD;
    }

    @Override protected void onEnd(AIPlayer ai) {
        if (blocking && ownsBlock(ai)) ai.getPlayer().clearActiveItem();
        blocking = false;
    }

    @Override public PrimitiveType getPrimitiveType() {
        return PrimitiveType.USE_ITEM;
    }
}
