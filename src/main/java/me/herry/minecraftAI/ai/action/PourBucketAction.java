package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.plan.BucketRules;
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

/**
 * 물 양동이의 물을 블록의 윗면에 붓는다. 그 블록의 윗면을 바라본 뒤 손에 든 양동이를 쓰면,
 * 서버가 시선을 따라가서 처음 닿는 블록의 그 면 쪽 칸에 물을 놓는다 (진짜 플레이어의 우클릭과 같은 경로).
 */
public final class PourBucketAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 60;
    private static final double MAX_REACH = 4.5;
    // 조금만 빗나가도 옆의 블록에 붓게 되므로 정확히 돌아선 뒤에 쓴다.
    private static final float FACING_TOLERANCE = 2.0F;
    private static final int MAX_AIM_TICKS = 20;

    private final BlockPoint support;
    private final BlockPoint cell;
    private World world;

    /**
     * @param support 윗면에 물을 부을 블록. 물은 그 바로 위 칸에 생긴다
     */
    public PourBucketAction(BlockPoint support) {
        super("PourBucket", TIMEOUT);
        this.support = support;
        this.cell = support.offset(0, 1, 0);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        if (BucketRules.evaporates(world.getEnvironment() == World.Environment.NETHER)) {
            fail("would evaporate");
            return;
        }
        if (!ai.getInventory().equip(Material.WATER_BUCKET)) {
            fail("no water bucket");
            return;
        }
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, support)) {
            fail("world changed");
            return;
        }
        if (!BucketRules.canPourInto(BukkitTerrainView.classify(Positions.block(world, cell).getType()),
                BukkitTerrainView.classify(Positions.block(world, support).getType()))) {
            fail("no support");
            return;
        }
        double x = support.x() + 0.5;
        double y = support.y() + 1.0;
        double z = support.z() + 0.5;
        if (player.getEyeLocation().distance(new Location(world, x, y, z)) > MAX_REACH) {
            fail("out of reach");
            return;
        }

        ai.getBody().lookAt(x, y, z);
        if (!ai.getBody().isFacing(x, y, z, FACING_TOLERANCE)) {
            if (getElapsed() >= MAX_AIM_TICKS) fail("cannot aim");
            return;
        }
        if (player.getInventory().getItemInMainHand().getType() != Material.WATER_BUCKET) {
            fail("no water bucket");
            return;
        }
        // 시선이 다른 블록이나 다른 면에 먼저 닿으면 물이 엉뚱한 칸에 놓인다. 붓기 전에 확인해서 물을 잃지 않는다.
        Location eye = player.getEyeLocation();
        RayTraceResult hit = world.rayTraceBlocks(eye, eye.getDirection(), MAX_REACH, FluidCollisionMode.NEVER, false);
        if (hit == null || hit.getHitBlock() == null || !Positions.of(hit.getHitBlock()).equals(support) || hit.getHitBlockFace() != BlockFace.UP) {
            fail("not visible");
            return;
        }

        ai.getBody().useItem();
        // 결과값만 믿지 않고 그 칸에 물이 실제로 생겼는지 본다. 시선이 다른 블록에 닿았으면 엉뚱한 곳에 부었을 수 있다.
        if (FillBucketAction.isSource(Positions.block(world, cell))) {
            ai.debug("Poured water at " + cell);
            succeed();
        } else if (player.getInventory().getItemInMainHand().getType() == Material.WATER_BUCKET) {
            fail("use denied");
        } else {
            ai.debug("Poured water, but not at " + cell);
            fail("missed");
        }
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
