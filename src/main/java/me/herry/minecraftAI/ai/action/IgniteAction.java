package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 부싯돌과 부시로 블록의 윗면에 불을 붙인다. 포탈 틀의 아랫줄 흑요석에 붙이면 틀 안이 네더 포탈이 된다.
 * 진짜 플레이어가 블록을 우클릭했을 때와 같은 서버 경로를 타지만, 그 경로는 거리와 시야를 다시 확인하지 않는다.
 * 그래서 손이 닿는지와 그 면이 실제로 보이는지를 여기서 먼저 확인한다 (벽 너머에 불을 붙이지 않는다).
 */
public final class IgniteAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 60;
    private static final double MAX_REACH = 4.5;
    private static final float FACING_TOLERANCE = 5.0F;
    private static final int MAX_AIM_TICKS = 20;

    private final BlockPoint floor;
    private final BlockPoint cell;
    private World world;

    /**
     * @param floor 윗면에 불을 붙일 블록. 불(또는 포탈)은 그 바로 위 칸에 생긴다
     */
    public IgniteAction(BlockPoint floor) {
        super("Ignite", TIMEOUT);
        this.floor = floor;
        this.cell = floor.offset(0, 1, 0);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        if (!ai.getInventory().equip(Material.FLINT_AND_STEEL)) {
            fail("no flint and steel");
            return;
        }
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, floor)) {
            fail("world changed");
            return;
        }
        if (isLit(Positions.block(world, cell).getType())) {
            succeed();
            return;
        }
        Location eye = player.getEyeLocation();
        Location top = new Location(world, floor.x() + 0.5, floor.y() + 1.0, floor.z() + 0.5);
        double distance = eye.distance(top);
        if (distance > MAX_REACH) {
            fail("out of reach");
            return;
        }

        ai.getBody().lookAt(top.getX(), top.getY(), top.getZ());
        if (!ai.getBody().isFacing(top.getX(), top.getY(), top.getZ(), FACING_TOLERANCE)) {
            if (getElapsed() >= MAX_AIM_TICKS) fail("cannot aim");
            return;
        }
        Vector direction = top.toVector().subtract(eye.toVector()).normalize();
        RayTraceResult hit = world.rayTraceBlocks(eye, direction, distance + 0.2, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null || !Positions.of(hit.getHitBlock()).equals(floor)) {
            fail("not visible");
            return;
        }
        if (player.getInventory().getItemInMainHand().getType() != Material.FLINT_AND_STEEL) {
            fail("no flint and steel");
            return;
        }

        ai.getBody().useItemOn(floor, BlockFace.UP);
        Material result = Positions.block(world, cell).getType();
        if (result == Material.NETHER_PORTAL) {
            ai.debug("Lit the nether portal at " + cell);
            succeed();
        } else if (result == Material.FIRE) {
            // 불만 붙고 포탈이 되지 않았으면 틀이 맞지 않는 것이다.
            fail("frame did not light");
        } else {
            fail("use denied");
        }
    }

    private static boolean isLit(Material type) {
        return type == Material.NETHER_PORTAL;
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.USE_ITEM;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(cell);
    }
}
