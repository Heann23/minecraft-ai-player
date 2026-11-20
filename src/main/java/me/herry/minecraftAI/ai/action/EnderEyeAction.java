package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import me.herry.minecraftAI.ai.primitive.runtime.PrimitiveAdapter;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;

/** A selected use of one already-held eye through the normal survival input route. */
public final class EnderEyeAction extends AbstractAction {
    private final PrimitiveCommand command;
    private final BlockPoint frame;

    public EnderEyeAction(PrimitiveCommand.ThrowEnderEye command) {
        this(command, null);
    }

    public EnderEyeAction(PrimitiveCommand.SocketEnderEye command) {
        this(command, command.frame());
    }

    private EnderEyeAction(PrimitiveCommand command, BlockPoint frame) {
        super(frame == null ? "ThrowEnderEye" : "SocketEnderEye", 40);
        this.command = command;
        this.frame = frame;
    }

    @Override protected void onStart(AIPlayer ai) {
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
    }

    @Override protected void onTick(AIPlayer ai) {
        var ready = PrimitiveAdapter.check(ai, command);
        if (!ready.allowed()) { fail(ready.reason()); return; }
        var player = ai.getPlayer();
        int before = player.getInventory().getItemInMainHand().getAmount();
        if (frame == null) {
            if (ai.getBody().useItem() && remainingEyes(ai) == before - 1) succeed();
            else fail("native eye throw did not spend the held eye");
            return;
        }
        var center = Positions.center(player.getWorld(), frame);
        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        var hit = player.rayTraceBlocks(PrimitiveAdapter.BLOCK_REACH, FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null
                || !frame.equals(Positions.of(hit.getHitBlock()))) return;
        ai.getBody().useItemOn(frame, hit.getHitBlockFace());
        if (hit.getHitBlock().getBlockData() instanceof org.bukkit.block.data.type.EndPortalFrame data
                && data.hasEye() && remainingEyes(ai) == before - 1) succeed();
        else fail("native frame use did not socket one held eye");
    }

    private static int remainingEyes(AIPlayer ai) {
        var held = ai.getPlayer().getInventory().getItemInMainHand();
        return held.getType() == Material.ENDER_EYE ? held.getAmount() : 0;
    }
}
