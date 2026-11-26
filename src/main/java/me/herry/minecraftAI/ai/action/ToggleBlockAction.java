package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Toggles a visible reachable non-iron door, trapdoor, fence gate, button, or lever
 * via the normal server block interaction path.
 */
public final class ToggleBlockAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 20;

    private final PrimitiveCommand.ToggleBlock command;
    private World world;

    public ToggleBlockAction(PrimitiveCommand.ToggleBlock command) {
        super("ToggleBlock", TIMEOUT);
        this.command = Objects.requireNonNull(command, "command");
    }

    public static boolean isToggleable(Block block) {
        Material type = block.getType();
        if (type == Material.IRON_DOOR || type == Material.IRON_TRAPDOOR) return false;
        return Tag.DOORS.isTagged(type)
                || Tag.TRAPDOORS.isTagged(type)
                || Tag.FENCE_GATES.isTagged(type)
                || Tag.BUTTONS.isTagged(type)
                || type == Material.LEVER;
    }

    public static String unavailableReason(AIPlayer ai, PrimitiveCommand.ToggleBlock cmd) {
        Player player = ai.getPlayer();
        World world = player.getWorld();
        if (!world.getUID().equals(cmd.world())) return "interactable world changed";
        BlockPoint target = cmd.target();
        if (target.y() < world.getMinHeight() || target.y() >= world.getMaxHeight() || !Positions.isLoaded(world, target))
            return "interactable unavailable";
        Block block = Positions.block(world, target);
        if (!isToggleable(block)) return "target is not a hand-toggleable block";
        if (ExactLootChestAction.visibleHitFace(player, target, 0.5, false) == null)
            return "interactable outside reach or blocked from view";
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
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, command.target())) {
            fail("world changed");
            return;
        }
        String reason = unavailableReason(ai, command);
        if (!reason.isEmpty()) {
            fail(reason);
            return;
        }
        BlockFace face = ExactLootChestAction.visibleHitFace(player, command.target(), 0.5, false);
        if (face == null) {
            fail("interactable outside reach or blocked from view");
            return;
        }
        Block block = Positions.block(world, command.target());
        String before = block.getBlockData().getAsString();
        Location center = Positions.center(world, command.target());
        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        ai.getBody().inputSneak(false);
        boolean consumed = ai.getBody().interactBlock(command.target(), face);
        String after = Positions.block(world, command.target()).getBlockData().getAsString();
        if (consumed || !before.equals(after)) succeed();
        else fail("block did not toggle");
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.INTERACT_BLOCK;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(command.target());
    }
}
