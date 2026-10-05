package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.perception.PerceptionSystem;
import me.herry.minecraftAI.ai.perception.Visibility;
import me.herry.minecraftAI.ai.primitive.PrimitiveAction;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.team.ShaftRegistry;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * 인벤토리의 블록을 주변의 빈자리에 설치한다.
 */
public final class PlaceBlockAction extends AbstractAction implements PrimitiveAction {
    private static final int TIMEOUT = 60;
    private static final int SEARCH_RADIUS = 2;
    private static final float FACING_TOLERANCE = 20.0F;
    private static final int MAX_AIM_TICKS = 12;

    private final Material material;
    private final @Nullable MemoryType rememberAs;
    private World world;
    private BlockPoint spot;

    /**
     * @param rememberAs 설치한 위치를 기억할 종류 (작업대, 화로 등). 기억할 필요가 없으면 null.
     */
    public PlaceBlockAction(Material material, @Nullable MemoryType rememberAs) {
        super("PlaceBlock", TIMEOUT);
        this.material = material;
        this.rememberAs = rememberAs;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        if (!material.isBlock() || !ai.getInventory().equip(material)) {
            fail("item not in inventory");
            return;
        }

        Player player = ai.getPlayer();
        world = player.getWorld();
        spot = findSpot(ai, material.createBlockData());
        if (spot == null) {
            fail("no place to put block");
            return;
        }
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        if (!player.getWorld().equals(world)) {
            fail("world changed");
            return;
        }
        if (!Positions.isLoaded(world, spot)) {
            fail("chunk not loaded");
            return;
        }

        Location center = Positions.center(world, spot);
        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        boolean aimed = ai.getBody().isFacing(center.getX(), center.getY(), center.getZ(), FACING_TOLERANCE);
        if (!aimed && getElapsed() < MAX_AIM_TICKS) return;

        if (place(player)) {
            remember(ai);
            succeed();
        } else {
            fail("place denied");
        }
    }

    private boolean place(Player player) {
        Block block = Positions.block(world, spot);
        BlockData data = material.createBlockData();
        // 조준하는 동안 누가 그 자리에 블록을 놓았거나 엔티티가 들어왔을 수 있다.
        if (!isFree(block, data)) return false;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != material) return false;

        BlockState replaced = block.getState();
        block.setBlockData(data, true);
        // 보호 플러그인이 설치를 막을 수 있도록 실제 플레이어가 설치할 때와 같은 이벤트를 보낸다.
        BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, block.getRelative(BlockFace.DOWN), hand, player, true, EquipmentSlot.HAND);
        if (!event.callEvent() || !event.canBuild()) {
            replaced.update(true, false);
            return false;
        }

        hand.setAmount(hand.getAmount() - 1);
        player.swingMainHand();
        world.playSound(block.getLocation(), data.getSoundGroup().getPlaceSound(), 1.0F, 1.0F);
        return true;
    }

    private void remember(AIPlayer ai) {
        if (rememberAs == null) return;
        ai.getMemory().rememberPermanent(rememberAs, world.getUID(), spot, ai.getTicks());
        // 직접 놓은 작업대와 화로는 다 쓴 뒤에 다시 챙겨 간다.
        if (rememberAs == MemoryType.WORKBENCH) ai.getMemory().rememberPermanent(MemoryType.OWN_WORKBENCH, world.getUID(), spot, ai.getTicks());
        if (rememberAs == MemoryType.FURNACE) ai.getMemory().rememberPermanent(MemoryType.OWN_FURNACE, world.getUID(), spot, ai.getTicks());
        ai.getTeam().sharePlaced(ai, rememberAs, spot);
        // 아직 거점이 없으면 처음 작업대를 놓은 자리를 임시 거점으로 삼는다. 집을 지으면 그 자리로 옮겨진다.
        if (rememberAs == MemoryType.WORKBENCH) ai.getWorldModel().ensureHome(world.getUID(), spot);
    }

    // 지금 서 있는 곳 주변에 이 블록을 놓을 자리가 있는지. 좁은 굴 안에서는 없을 수 있다.
    public static boolean hasSpot(AIPlayer ai, Material material) {
        return material.isBlock() && findSpot(ai, material.createBlockData()) != null;
    }

    /**
     * 발밑과 머리 칸을 제외하고, 눈에 보이는 가장 가까운 설치 가능한 자리를 찾는다.
     * 지나다니는 길(파 놓은 굴의 발판, 방금 걸어온 칸)에는 놓지 않는다. 좁은 계단 굴에서 윗단에 화로를 놓으면 스스로 갇힌다.
     */
    private static @Nullable BlockPoint findSpot(AIPlayer ai, BlockData data) {
        Player player = ai.getPlayer();
        World world = player.getWorld();
        ShaftRegistry shafts = ai.getTeam().getShafts();
        Set<BlockPoint> walked = new HashSet<>();
        for (MemorySystem.Visit visit : ai.getMemory().getRecentPath()) {
            if (visit.world().equals(world.getUID())) walked.add(visit.pos());
        }
        Location location = player.getLocation();
        int px = location.getBlockX();
        int py = location.getBlockY();
        int pz = location.getBlockZ();
        Location eye = player.getEyeLocation();
        Visibility.Opacity opacity = PerceptionSystem.opacityOf(world);

        BlockPoint best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int distance = dx * dx + dz * dz + dy * dy * 2;
                    if (distance >= bestDistance) continue;
                    int x = px + dx;
                    int y = py + dy;
                    int z = pz + dz;
                    if (y <= world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    BlockPoint cell = new BlockPoint(x, y, z);
                    if (shafts.isStep(world.getUID(), cell) || walked.contains(cell)) continue;
                    if (!isFree(world.getBlockAt(x, y, z), data)) continue;
                    // 벽이나 모퉁이 너머의 빈칸에는 놓지 않는다. 실제 플레이어는 보이는 자리에만 놓을 수 있고,
                    // 그런 자리에 놓은 작업대나 화로는 손이 닿지 않아서 쓰지도 못한다.
                    if (!Visibility.canSeePoint(opacity, eye.getX(), eye.getY(), eye.getZ(), x + 0.5, y + 0.5, z + 0.5)) continue;
                    best = cell;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static boolean isFree(Block block, BlockData data) {
        if (block.isLiquid() || !(block.isEmpty() || block.isReplaceable())) return false;
        if (!block.getRelative(BlockFace.DOWN).getType().isSolid()) return false;
        if (!block.canPlace(data)) return false;
        // 그 칸에 떨어져 있는 아이템은 블록에 밀려나므로 막지 않는다. 좁은 굴에서는 캔 돌이 유일한 빈칸에 떨어져 있곤 한다.
        return !BlockPlacing.hasBlockingEntity(block);
    }

    @Override
    public PrimitiveType getPrimitiveType() {
        return PrimitiveType.PLACE_BLOCK;
    }

    @Override
    public PrimitiveTarget getTarget() {
        return new PrimitiveTarget.Item(material.name(), 1);
    }
}
