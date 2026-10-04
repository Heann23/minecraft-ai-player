package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * 정해진 칸에 흙이나 조약돌 같은 블록을 놓는다. 협곡이나 구덩이 위로 다리를 놓을 때 발 앞의 빈칸을 채운다.
 */
public final class PlaceFillerAction extends AbstractAction {
    private static final int TIMEOUT = 60;
    private static final double MAX_REACH = 4.5;
    private static final float FACING_TOLERANCE = 20.0F;
    private static final int MAX_AIM_TICKS = 10;
    private static final float STEP_BACK_FACING = 45.0F;

    private final BlockPoint target;
    private World world;

    public PlaceFillerAction(BlockPoint target) {
        super("PlaceFiller", TIMEOUT);
        this.target = target;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        int slot = ai.getInventory().findFillerSlot();
        if (slot < 0) {
            fail("no blocks to place");
            return;
        }
        ai.getInventory().equipSlot(slot);
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
        // 발 앞의 빈칸을 내려다보는 동안 미끄러져 떨어지지 않도록 웅크린다.
        ai.getBody().inputSneak(true);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, target)) {
            fail("world changed");
            return;
        }
        Block block = Positions.block(world, target);
        if (block.getType().isSolid()) {
            succeed();
            return;
        }

        Location center = Positions.center(world, target);
        if (player.getEyeLocation().distance(center) > MAX_REACH) {
            fail("out of reach");
            return;
        }
        // 자기 몸이 놓을 칸에 걸쳐 있으면 놓을 수 없다. 그 칸을 바라본 채 뒷걸음으로 비켜난 다음에 놓는다.
        if (player.getBoundingBox().overlaps(BoundingBox.of(block))) {
            double eyeY = player.getEyeLocation().getY();
            ai.getBody().lookAt(center.getX(), eyeY, center.getZ());
            boolean facing = ai.getBody().isFacing(center.getX(), eyeY, center.getZ(), STEP_BACK_FACING);
            ai.getBody().inputMove(facing ? -1.0F : 0.0F, 0.0F);
            return;
        }
        ai.getBody().inputMove(0.0F, 0.0F);
        if (!BlockPlacing.canPlaceAt(block)) {
            fail("cannot place there");
            return;
        }

        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        boolean aimed = ai.getBody().isFacing(center.getX(), center.getY(), center.getZ(), FACING_TOLERANCE);
        if (!aimed && getElapsed() < MAX_AIM_TICKS) return;

        if (!InventorySystem.isFiller(player.getInventory().getItemInMainHand().getType())) {
            fail("no blocks to place");
            return;
        }
        if (BlockPlacing.place(player, block)) succeed();
        else fail("place denied");
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputSneak(false);
    }
}
