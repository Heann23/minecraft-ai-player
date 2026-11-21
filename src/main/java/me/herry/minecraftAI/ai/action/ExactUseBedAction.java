package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Interacts with a visible reachable bed via the normal server block-use path:
 * sets Overworld respawn during daytime, sleeps and waits for morning at night,
 * and respects vanilla dimension physics without hardcoded rules.
 */
public final class ExactUseBedAction extends AbstractAction implements PrimitiveAction {
    public static final int ACTION_TICKS = 240;
    private static final int MAX_SLEEP_TICKS = 220;

    private final PrimitiveCommand.UseBed command;
    private World world;
    private boolean sleeping;
    private int sleptAt;

    public ExactUseBedAction(PrimitiveCommand.UseBed command) {
        super("ExactUseBed", ACTION_TICKS);
        this.command = Objects.requireNonNull(command, "command");
    }

    public static String unavailableReason(AIPlayer ai, PrimitiveCommand.UseBed cmd) {
        Player player = ai.getPlayer();
        World world = player.getWorld();
        if (!world.getUID().equals(cmd.world())) return "bed world changed";
        BlockPoint bed = cmd.bed();
        if (bed.y() < world.getMinHeight() || bed.y() >= world.getMaxHeight() || !Positions.isLoaded(world, bed))
            return "bed unavailable";
        Block block = Positions.block(world, bed);
        if (!Tag.BEDS.isTagged(block.getType())) return "target is not a bed";
        if (ExactLootChestAction.visibleHitFace(player, bed, 0.35, true) == null)
            return "bed outside reach or blocked from view";
        return "";
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        String reason = unavailableReason(ai, command);
        if (!reason.isEmpty()) {
            fail(reason);
            return;
        }
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, command.bed())) {
            fail("world changed");
            return;
        }
        if (!sleeping) {
            String reason = unavailableReason(ai, command);
            if (!reason.isEmpty()) {
                fail(reason);
                return;
            }
            BlockFace face = ExactLootChestAction.visibleHitFace(player, command.bed(), 0.35, true);
            if (face == null) {
                fail("bed outside reach or blocked from view");
                return;
            }
            Location aim = new Location(world, command.bed().x() + 0.5, command.bed().y() + 0.35, command.bed().z() + 0.5);
            ai.getBody().lookAt(aim.getX(), aim.getY(), aim.getZ());
            ai.getBody().inputSneak(false);
            boolean consumed = ai.getBody().interactBlock(command.bed(), face);
            if (player.isSleeping()) {
                sleeping = true;
                sleptAt = getElapsed();
                return;
            }
            if (consumed) succeed();
            else fail("bed interaction denied");
            return;
        }
        if (!player.isSleeping()) {
            succeed();
        } else if (getElapsed() - sleptAt >= MAX_SLEEP_TICKS) {
            player.wakeup(true);
            succeed();
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (sleeping && player.isSleeping()) player.wakeup(false);
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.INTERACT_BLOCK;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(command.bed());
    }
}
