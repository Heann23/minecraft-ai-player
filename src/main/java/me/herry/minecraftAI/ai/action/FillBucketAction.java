package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;

/**
 * 빈 양동이로 물을 뜬다. 물을 바라본 뒤 손에 든 양동이를 쓰면, 서버가 시선을 따라가서 처음 닿는 원천 물을 떠 준다.
 * 진짜 플레이어가 우클릭했을 때와 같은 경로라서 거리, 가로막은 블록, 보호 플러그인의 판정을 그대로 받는다.
 */
public final class FillBucketAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 60;
    private static final double MAX_REACH = 4.5;
    // 서버는 시선이 실제로 닿는 블록을 본다. 조금만 빗나가도 옆의 블록을 누르게 되므로 정확히 돌아선 뒤에 쓴다.
    private static final float FACING_TOLERANCE = 2.0F;
    private static final int MAX_AIM_TICKS = 20;
    // 원천 물의 수면은 블록의 윗면보다 조금 낮다. 그 바로 아래를 겨눈다.
    private static final double AIM_HEIGHT = 0.8;

    private final BlockPoint source;
    private World world;
    private int carried;

    public FillBucketAction(BlockPoint source) {
        super("FillBucket", TIMEOUT);
        this.source = source;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        carried = ai.getInventory().count(Material.WATER_BUCKET);
        if (!ai.getInventory().equip(Material.BUCKET)) {
            fail("no bucket");
            return;
        }
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, source)) {
            fail("world changed");
            return;
        }
        if (!isSource(Positions.block(world, source))) {
            // 흐르는 물은 뜰 수 없다. 누가 떠 갔거나 물길이 바뀐 것이므로 잊는다.
            ai.getMemory().forget(MemoryType.WATER_SOURCE, world.getUID(), source);
            fail("not a source");
            return;
        }
        double x = source.x() + 0.5;
        double y = source.y() + AIM_HEIGHT;
        double z = source.z() + 0.5;
        if (player.getEyeLocation().distance(new Location(world, x, y, z)) > MAX_REACH) {
            fail("out of reach");
            return;
        }

        ai.getBody().lookAt(x, y, z);
        if (!ai.getBody().isFacing(x, y, z, FACING_TOLERANCE)) {
            if (getElapsed() >= MAX_AIM_TICKS) fail("cannot aim");
            return;
        }
        if (player.getInventory().getItemInMainHand().getType() != Material.BUCKET) {
            fail("no bucket");
            return;
        }

        ai.getBody().useItem();
        // 결과값만 믿지 않고 물 양동이가 실제로 생겼는지 본다.
        if (ai.getInventory().count(Material.WATER_BUCKET) > carried) {
            ai.debug("Filled a bucket with water at " + source);
            succeed();
            return;
        }
        // 시선이 다른 블록에 가로막혔다. 같은 물을 계속 고르지 않도록 잊는다. 주변을 다시 훑으면 보이는 물이 새로 적힌다.
        ai.getMemory().forget(MemoryType.WATER_SOURCE, world.getUID(), source);
        fail("use denied");
    }

    public static boolean isSource(Block block) {
        return block.getType() == Material.WATER && block.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0;
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.USE_ITEM;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Block(source);
    }
}
