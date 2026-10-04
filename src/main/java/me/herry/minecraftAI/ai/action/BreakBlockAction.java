package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * 블록 하나를 캔다. 블록을 바로 없애지 않고, 실제 플레이어처럼 바라보고 팔을 휘두르며
 * 들고 있는 도구에 맞는 시간만큼 걸려서 부순다.
 */
public final class BreakBlockAction extends AbstractAction {
    private static final int TIMEOUT = 600;
    // 서바이벌 모드에서 블록에 손이 닿는 거리(4.5칸)에 약간의 여유를 둔 값
    private static final double MAX_REACH = 4.9;
    private static final float FACING_TOLERANCE = 20.0F;
    // 이 시간이 지나면 정확히 바라보지 못했더라도 캐기 시작한다.
    private static final int MAX_AIM_TICKS = 15;
    private static final int SWING_INTERVAL = 4;
    private static final double CRACK_VIEW_DISTANCE_SQ = 32.0 * 32.0;
    private static final long DANGER_TTL = 6000L;
    // 목표를 가리는 블록을 이만큼 치우고도 보이지 않으면 포기한다.
    private static final int MAX_OBSTRUCTIONS = 4;
    // 캔 자리 위에 모래나 자갈이 있으면 그 자리로 떨어져서 다시 막힌다. 떨어지기를 이만큼 기다렸다가 그것도 이어서 캔다.
    private static final int FALL_WAIT_TICKS = 12;
    private static final int MAX_REFILLS = 10;
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final BlockPoint target;
    private final boolean homeAllowed;
    // 물에 닿은 블록은 캐지 않을지. 광석을 캘 때 쓴다. 물 옆의 블록을 캐면 굴에 물이 차서 떠 있는 채로 아무것도 못 하게 된다.
    private final boolean avoidWater;
    private World world;
    private Material expected;
    // 지금 실제로 캐고 있는 블록. 목표 블록이 다른 블록에 가려져 있으면 가리고 있는 블록이 된다.
    private BlockPoint current;
    private Material currentType;
    private float progress;
    private int aimTicks;
    private int obstructionsCleared;
    private boolean crackShown;
    // 0 이상이면 캔 자리로 모래나 자갈이 떨어지기를 기다리는 중이다.
    private int settleTicks = -1;
    private int refills;

    public BreakBlockAction(BlockPoint target) {
        this(target, false);
    }

    /**
     * @param homeAllowed 자기 집의 블록도 캘 수 있는지. 집을 짓는 중에 자리를 비울 때만 true 로 쓴다.
     *                    그 밖의 채굴이나 길 내기가 집의 벽과 바닥을 허물지 않게 하기 위한 구분이다.
     */
    public BreakBlockAction(BlockPoint target, boolean homeAllowed) {
        this(target, homeAllowed, false);
    }

    private BreakBlockAction(BlockPoint target, boolean homeAllowed, boolean avoidWater) {
        super("BreakBlock", TIMEOUT);
        this.target = target;
        this.homeAllowed = homeAllowed;
        this.avoidWater = avoidWater;
    }

    /**
     * 돌이나 광석을 캐는 행동. 대상이나 그것을 가린 블록이 물에 닿아 있으면 캐지 않는다.
     */
    public static BreakBlockAction mine(BlockPoint target) {
        return new BreakBlockAction(target, false, true);
    }

    @Override
    protected void onStart(AIPlayer ai) {
        Player player = ai.getPlayer();
        world = player.getWorld();
        if (!Positions.isLoaded(world, target)) {
            fail("chunk not loaded");
            return;
        }

        Block block = Positions.block(world, target);
        expected = block.getType();
        // 앞선 행동이 가린 블록을 치우다가 이미 캐 버렸으면 할 일이 없다. 실패로 세면 멀쩡한 계획을 버리게 된다.
        if (expected.isAir()) {
            succeed();
            return;
        }
        if (block.isLiquid()) {
            fail("target disappeared");
            return;
        }

        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
        selectCurrent(ai, player);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world)) {
            fail("world changed");
            return;
        }
        // 캐는 도중에 멀리 순간이동되었을 수 있다.
        if (!Positions.isLoaded(world, target) || !Positions.isLoaded(world, current)) {
            fail("chunk not loaded");
            return;
        }
        if (settleTicks >= 0) {
            awaitRefill(ai, player);
            return;
        }
        if (Positions.block(world, target).getType() != expected) {
            fail("target disappeared");
            return;
        }

        Block block = Positions.block(world, current);
        if (block.getType() != currentType) {
            // 가리고 있던 블록이 다른 이유로 사라졌으면 다시 조준한다.
            selectCurrent(ai, player);
            return;
        }

        Location center = Positions.center(world, current);
        if (player.getEyeLocation().distance(center) > MAX_REACH) {
            fail("out of reach");
            return;
        }

        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        ai.getBody().inputJump(player.isInWater());
        boolean aimed = ai.getBody().isFacing(center.getX(), center.getY(), center.getZ(), FACING_TOLERANCE);
        if (!aimed && ++aimTicks < MAX_AIM_TICKS) return;

        // 도구, 물속 여부, 채굴 피로 등이 모두 반영된 한 틱의 진행도
        float speed = block.getBreakSpeed(player);
        if (speed <= 0.0F) {
            fail("cannot break");
            return;
        }
        progress += speed;
        if (getElapsed() % SWING_INTERVAL == 0) player.swingMainHand();
        showCrack(player, center, Math.min(progress, 1.0F));
        if (progress < 1.0F) return;

        if (!player.breakBlock(block) || block.getType() == currentType) {
            // 다른 플러그인이 막은 경우
            fail("break denied");
            return;
        }
        showCrack(player, center, 0.0F);
        ai.onBlockBroken(current, currentType);
        if (current.equals(target)) {
            forgetResource(ai);
            if (refills < MAX_REFILLS && Positions.block(world, target.offset(0, 1, 0)).getType().hasGravity()) settleTicks = 0;
            else succeed();
        } else {
            obstructionsCleared++;
            selectCurrent(ai, player);
        }
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        ai.getBody().inputJump(false);
        if (crackShown && world != null && current != null) showCrack(ai.getPlayer(), Positions.center(world, current), 0.0F);
    }

    // 떨어진 모래나 자갈이 캔 자리를 다시 채웠으면 그것을 이어서 캔다. 굴 앞이 자갈 더미여도 한 번의 행동으로 길이 난다.
    private void awaitRefill(AIPlayer ai, Player player) {
        Material fallen = Positions.block(world, target).getType();
        if (fallen.hasGravity()) {
            settleTicks = -1;
            refills++;
            expected = fallen;
            selectCurrent(ai, player);
        } else if (++settleTicks > FALL_WAIT_TICKS) {
            succeed();
        }
    }

    /**
     * 눈에서 목표 블록까지 시선을 그어 보고, 다른 블록이 가리고 있으면 그 블록부터 캔다.
     * 실제 플레이어는 벽 너머의 블록을 캘 수 없고, 앞을 막은 블록을 먼저 치워야 한다.
     */
    private void selectCurrent(AIPlayer ai, Player player) {
        BlockPoint next = target;
        Block obstruction = findObstruction(player);
        if (obstruction != null) {
            if (obstructionsCleared >= MAX_OBSTRUCTIONS) {
                fail("target not visible");
                return;
            }
            next = Positions.of(obstruction);
        }
        // 대상 자체가 광석 발판인 경우도 보호한다. 가리는 블록만 검사하면 발판의 석탄을 직접 캐 버린다.
        if (ai.getTeam().getShafts().isStep(world.getUID(), next.offset(0, 1, 0))) {
            ai.getMemory().rememberUnreachable(world.getUID(), target, ai.getTicks());
            fail("would cut the way out");
            return;
        }

        Block block = Positions.block(world, next);
        if (block.getType().getHardness() < 0.0F) {
            fail("unbreakable block");
            return;
        }
        Base home = ai.getWorldModel().homeIn(world.getUID());
        if (!homeAllowed && home != null && home.isInsideBuilding(world.getUID(), next)) {
            fail("part of home");
            return;
        }
        // 용암 옆의 블록을 캐면 용암이 흘러나온다.
        if (touchesLava(block)) {
            ai.getMemory().remember(MemoryType.DANGER_PLACE, world.getUID(), target, ai.getTicks(), DANGER_TTL);
            fail("lava next to target");
            return;
        }
        // 물 옆의 블록을 캐면 물이 굴로 흘러든다. 같은 대상을 다시 고르지 않도록 갈 수 없는 곳으로 기억해 둔다.
        if (avoidWater && touches(block, Material.WATER)) {
            ai.getMemory().rememberUnreachable(world.getUID(), target, ai.getTicks());
            fail("water next to target");
            return;
        }

        if (!next.equals(target)) ai.debug("Clearing " + block.getType() + " at " + next + " that hides " + target);
        current = next;
        currentType = block.getType();
        progress = 0.0F;
        aimTicks = 0;
        int toolSlot = ai.getInventory().bestToolSlot(block);
        // 맨손보다 빨리 캐는 도구가 없으면, 들고 있던 도구가 쓸데없이 닳지 않게 내려놓고 캔다.
        if (toolSlot >= 0) ai.getInventory().equipSlot(toolSlot);
        else ai.getInventory().restHand();
    }

    // 눈과 목표 블록 사이를 가로막는 첫 번째 블록. 목표가 바로 보이면 null.
    private @Nullable Block findObstruction(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = Positions.center(world, target).toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (distance < 1.0E-3) return null;

        // 풀이나 꽃처럼 통과할 수 있는 블록은 시야를 가리지 않는 것으로 본다.
        RayTraceResult hit = world.rayTraceBlocks(eye, direction.normalize(), distance + 0.5, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) return null;
        Block block = hit.getHitBlock();
        return Positions.of(block).equals(target) ? null : block;
    }

    // 금 가는 모습은 원래 캐는 플레이어의 클라이언트가 그리므로, 주변 플레이어에게 직접 보내 준다.
    private void showCrack(Player self, Location center, float value) {
        crackShown = value > 0.0F;
        for (Player viewer : world.getPlayers()) {
            if (viewer.equals(self) || viewer.getLocation().distanceSquared(center) > CRACK_VIEW_DISTANCE_SQ) continue;
            viewer.sendBlockDamage(center, value, self.getEntityId());
        }
    }

    private static boolean touchesLava(Block block) {
        return touches(block, Material.LAVA);
    }

    private static boolean touches(Block block, Material liquid) {
        World world = block.getWorld();
        for (BlockFace face : FACES) {
            int x = block.getX() + face.getModX();
            int z = block.getZ() + face.getModZ();
            // 옆 청크가 로드되지 않았으면 읽지 않는다 (동기 청크 로드 방지).
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (block.getRelative(face).getType() == liquid) return true;
        }
        return false;
    }

    private void forgetResource(AIPlayer ai) {
        MemorySystem memory = ai.getMemory();
        memory.forget(MemoryType.TREE, world.getUID(), target);
        memory.forget(MemoryType.STONE, world.getUID(), target);
        memory.forget(MemoryType.COAL_ORE, world.getUID(), target);
        memory.forget(MemoryType.IRON_ORE, world.getUID(), target);
        memory.forget(MemoryType.DIAMOND_ORE, world.getUID(), target);
        memory.forget(MemoryType.WORKBENCH, world.getUID(), target);
        memory.forget(MemoryType.OWN_WORKBENCH, world.getUID(), target);
        memory.forget(MemoryType.FURNACE, world.getUID(), target);
        memory.forget(MemoryType.OWN_FURNACE, world.getUID(), target);
    }
}
