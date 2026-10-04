package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * 집의 침대에서 잔다. 실제 플레이어가 침대를 우클릭했을 때와 같은 절차를 거치므로,
 * 밤이 아니거나 몬스터가 가까이 있으면 잘 수 없고, 자면 리스폰 지점이 이 침대로 정해진다.
 * 다른 사람이 깨어 있어서 밤이 넘어가지 않으면 침대에 누워만 있지 않고 일어난다.
 */
public final class SleepAction extends AbstractAction {
    // 잠들고 5초가 지나면 밤이 넘어간다. 그보다 넉넉히 기다려 보고 안 넘어가면 일어난다.
    private static final int MAX_SLEEP_TICKS = 300;
    private static final int TIMEOUT = MAX_SLEEP_TICKS + 100;
    // 한 번 자고 나면(또는 잘 수 없었으면) 이 시간 동안은 다시 자러 가지 않는다.
    public static final long RETRY_TICKS = 6000L;

    private final BlockPoint bed;
    private World world;
    private boolean sleeping;
    private int sleptAt;

    public SleepAction(BlockPoint bed) {
        super("Sleep", TIMEOUT);
        this.bed = bed;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        world = ai.getPlayer().getWorld();
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world) || !Positions.isLoaded(world, bed)) {
            fail("world changed");
            return;
        }
        if (!sleeping) {
            Block block = Positions.block(world, bed);
            if (!Tag.BEDS.isTagged(block.getType())) {
                // 침대가 부서졌다. 거점 정보에서 지운다.
                Base home = ai.getWorldModel().homeIn(world.getUID());
                if (home != null) home.setBed(null);
                ai.getMemory().forget(MemoryType.BED, world.getUID(), bed);
                fail("bed disappeared");
                return;
            }
            if (!player.sleep(block.getLocation(), false)) {
                fail("cannot sleep now");
                return;
            }
            sleeping = true;
            sleptAt = getElapsed();
            ai.debug("Sleeping in the bed at " + bed);
            return;
        }

        if (!player.isSleeping()) {
            // 아침이 되어 일어났다.
            ai.getTeam().say(ai, Phrases.goodMorning(), false);
            succeed();
        } else if (getElapsed() - sleptAt > MAX_SLEEP_TICKS) {
            player.wakeup(true);
            succeed();
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        // 다시 자러 가지 않는 시간은 잠이 끝난 뒤부터 센다. 시작할 때 걸어 두면 "지금은 잘 수 없음"이 되어
        // 자는 도중에 잠자기 목표가 사라지고, 다른 목표가 끼어들어 바로 깨우게 된다.
        ai.setSleepRetryAfter(ai.getTicks() + RETRY_TICKS);
        Player player = ai.getPlayer();
        if (sleeping && player.isSleeping()) player.wakeup(false);
    }
}
