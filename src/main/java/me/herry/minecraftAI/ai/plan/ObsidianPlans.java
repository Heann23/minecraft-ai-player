package me.herry.minecraftAI.ai.plan;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.BreakBlockAction;
import me.herry.minecraftAI.ai.action.CenterOnBlockAction;
import me.herry.minecraftAI.ai.action.FillBucketAction;
import me.herry.minecraftAI.ai.action.MoveToAction;
import me.herry.minecraftAI.ai.action.PickupItemAction;
import me.herry.minecraftAI.ai.action.PourBucketAction;
import me.herry.minecraftAI.ai.action.RememberPlaceAction;
import me.herry.minecraftAI.ai.action.WaitAction;
import me.herry.minecraftAI.ai.goal.Milestone;
import me.herry.minecraftAI.ai.memory.MemoryEntry;
import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.navigation.ObsidianSite;
import me.herry.minecraftAI.ai.navigation.PathGoal;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 흑요석을 모으는 계획. 용암 호수 가장자리에 받침을 놓고 올라서서, 옆에 물을 부어 호수 표면을 흑요석으로 만든 뒤 캔다.
 *
 * 진행 상태를 따로 들고 다니지 않고 매번 월드를 보고 다음 한 걸음을 정한다:
 *   받침 위에 있고 옆에 부어 둔 물이 있다 → 손이 닿는 흑요석을 캔다. 다 캤으면 물을 거두고 떨어진 것을 줍는다
 *   받침 위에 있다                         → 물을 붓는다
 *   자리(둑)에 서 있다                     → 받침을 놓고 올라선다
 *   아는 용암 호수가 있다                  → 그 가장자리의 자리로 간다
 *   없다                                   → 다이아몬드를 찾던 깊이로 내려가서 찾는다
 * 도중에 싸우거나 자리를 떠났다면 부어 둔 물은 물 뜨기 목표(FILL_BUCKET)가 거두고, 처음부터 다시 한다.
 */
public final class ObsidianPlans {
    private static final double LAKE_RANGE = 48.0;
    private static final int SITE_RADIUS = 6;
    // 부은 물이 호수 위로 퍼져서 용암이 굳을 때까지, 거둔 물이 다 빠질 때까지 기다리는 시간
    private static final int SPREAD_WAIT_TICKS = 50;
    private static final int DRAIN_WAIT_TICKS = 80;
    private static final double LOOSE_RADIUS = 8.0;
    // 쓰고 난 자리는 이만큼 동안 다시 고르지 않는다. 손이 닿는 용암은 이미 다 굳혔다.
    private static final long USED_SITE_TTL = 6000L;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private ObsidianPlans() {
    }

    /**
     * 받침 위에서 물을 흘려 놓고 캐는 중인지. 이 동안에는 물이 가방에 없어도 물을 뜨러 가지 않고,
     * 구멍에 떨어진 흑요석도 물을 거둔 뒤에 줍는다.
     */
    public static boolean isWorking(AIPlayer ai) {
        if (!ai.getBody().isGrounded()) return false;
        World world = ai.getPlayer().getWorld();
        BlockPoint feet = ai.getPosition();
        if (pouredWater(world, feet) == null) return false;
        BlockPoint stand = feet.offset(0, -2, 0);
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        // 보통 물가와 구별한다: 손이 닿는 곳에 흑요석이나 용암이 있어야 한다.
        return !ObsidianSite.reachable(stand, cell -> isObsidianOrLava(world, cell), terrain).isEmpty();
    }

    public static boolean knowsLake(AIPlayer ai) {
        return nearestLake(ai) != null;
    }

    static List<Action> gatherObsidian(AIPlayer ai) {
        if (!ai.getBody().isGrounded()) return List.of();
        Player player = ai.getPlayer();
        World world = player.getWorld();
        BukkitTerrainView terrain = new BukkitTerrainView(world);
        BlockPoint feet = ai.getPosition();

        BlockPoint water = pouredWater(world, feet);
        if (water != null && isWorking(ai)) return work(ai, world, terrain, feet.offset(0, -2, 0), water);
        if (!ai.getInventory().has(Material.WATER_BUCKET)) return List.of();

        Predicate<BlockPoint> lava = cell -> isLakeSurface(world, cell);
        MemorySystem memory = ai.getMemory();
        // 한 번 쓴 자리에서는 다시 붓지 않는다. 거기서 캘 수 있는 것은 이미 다 캤다. 같은 자리에서 붓고 거두기만 되풀이하게 된다.
        Predicate<BlockPoint> used = stand -> memory.contains(MemoryType.CHECKED_PLACE, world.getUID(), stand, ai.getTicks());
        ObsidianSite onPedestal = used.test(feet.offset(0, -2, 0)) ? null : ObsidianSite.at(terrain, lava, feet.offset(0, -2, 0), true);
        if (onPedestal != null && terrain.classify(feet.x(), feet.y() - 1, feet.z()) == BlockClass.SOLID) {
            ai.debug("Pouring water next to the lava lake, on " + onPedestal.pour());
            // 받침의 한가운데에 서야 한다. 붓는 쪽에서 먼 가장자리에 서 있으면 시선이 받침의 모서리에 걸려서 물이 받침 위(자기 발밑)에 놓인다.
            return List.of(new CenterOnBlockAction(feet), new PourBucketAction(onPedestal.pour()), new WaitAction(SPREAD_WAIT_TICKS));
        }
        if (!used.test(feet.offset(0, -1, 0)) && ObsidianSite.at(terrain, lava, feet.offset(0, -1, 0), false) != null) {
            // 한 칸 높은 받침 위에 서 있어야 퍼지는 물에 떠밀리지 않는다.
            return TerrainPlans.pillarUp(ai, null);
        }

        BlockPoint lake = nearestLake(ai);
        if (lake == null) {
            // 다이아몬드를 찾던 깊이의 동굴에는 용암 호수가 흔하다.
            return GatherPlans.findDiamond(ai);
        }
        ObsidianSite site = ObsidianSite.find(terrain, lava, lake, SITE_RADIUS,
                stand -> used.test(stand) || memory.contains(MemoryType.UNREACHABLE, world.getUID(), stand.offset(0, 1, 0), ai.getTicks()));
        if (site == null) {
            // 설 자리가 없는 호수다 (사방이 용암이거나 천장이 낮다). 다른 호수를 찾게 잊는다.
            ai.debug("No place to stand by the lava at " + lake);
            memory.forget(MemoryType.LAVA_LAKE, world.getUID(), lake);
            return List.of();
        }
        ai.debug("Lava lake at " + lake + ", going to stand on " + site.stand() + " (" + site.cells() + " cells in reach)");
        return List.of(new MoveToAction(PathGoal.arrive(site.stand().offset(0, 1, 0), 0.3), true));
    }

    // 받침 위에서: 손이 닿는 흑요석을 하나 캔다. 다 모았거나 더 캘 것이 없으면 물을 거두고 줍는다.
    private static List<Action> work(AIPlayer ai, World world, BukkitTerrainView terrain, BlockPoint stand, BlockPoint water) {
        Player player = ai.getPlayer();
        int have = ai.getInventory().count(Material.OBSIDIAN) + looseObsidian(player);
        if (have < Milestone.PORTAL_OBSIDIAN) {
            int dry = 0;
            int hidden = 0;
            for (BlockPoint cell : ObsidianSite.reachable(stand, point -> typeAt(world, point) == Material.OBSIDIAN, terrain)) {
                if (!isMinable(world, cell)) {
                    dry++;
                } else if (!canSee(player, world, cell)) {
                    hidden++;
                } else {
                    ai.debug("Mining obsidian at " + cell + " (" + have + " so far)");
                    return List.of(BreakBlockAction.underWater(cell));
                }
            }
            ai.debug("No more obsidian to mine from here: " + dry + " without water above or beside lava, " + hidden + " out of sight");
        }
        ai.debug("Taking the water back from " + water + " with " + have + " obsidian mined");
        return List.of(new FillBucketAction(water), new WaitAction(DRAIN_WAIT_TICKS), new PickupItemAction(LOOSE_RADIUS, false),
                new RememberPlaceAction(MemoryType.CHECKED_PLACE, stand, USED_SITE_TTL));
    }

    // 위에 물이 흐르고 있고 옆에 용암이 없는 흑요석만 캔다. 위에 물이 없으면 아래에서 드러난 용암에 캔 것이 타 버린다.
    private static boolean isMinable(World world, BlockPoint cell) {
        if (typeAt(world, cell.offset(0, 1, 0)) != Material.WATER) return false;
        for (int[] side : SIDES) {
            if (typeAt(world, cell.offset(side[0], 0, side[1])) == Material.LAVA) return false;
        }
        return true;
    }

    // 받침이나 둑에 가려진 칸은 고르지 않는다. 캐는 행동은 가린 블록부터 치우는데, 그것이 서 있는 받침일 수 있다.
    private static boolean canSee(Player player, World world, BlockPoint cell) {
        Location eye = player.getEyeLocation();
        Vector direction = Positions.center(world, cell).toVector().subtract(eye.toVector());
        double distance = direction.length();
        RayTraceResult hit = world.rayTraceBlocks(eye, direction.normalize(), distance + 0.5, FluidCollisionMode.NEVER, true);
        return hit != null && hit.getHitBlock() != null && Positions.of(hit.getHitBlock()).equals(cell);
    }

    // 발보다 한 칸 낮은 옆 칸에 있는 물 원천. 받침 위에서 옆의 둑에 부어 둔 물이 그 자리에 있다.
    private static @Nullable BlockPoint pouredWater(World world, BlockPoint feet) {
        for (int[] side : SIDES) {
            BlockPoint cell = feet.offset(side[0], -1, side[1]);
            if (Positions.isLoaded(world, cell) && cell.y() >= world.getMinHeight() && FillBucketAction.isSource(Positions.block(world, cell))) {
                return cell;
            }
        }
        return null;
    }

    private static int looseObsidian(Player player) {
        int count = 0;
        for (Entity entity : player.getNearbyEntities(LOOSE_RADIUS, LOOSE_RADIUS, LOOSE_RADIUS)) {
            if (entity instanceof Item item && item.isValid() && item.getItemStack().getType() == Material.OBSIDIAN) {
                count += item.getItemStack().getAmount();
            }
        }
        return count;
    }

    // 기억하는 용암 호수 중 가장 가까운 것. 더는 용암이 아닌 것(굳혔거나 누가 퍼 간 것)은 잊는다.
    private static @Nullable BlockPoint nearestLake(AIPlayer ai) {
        World world = ai.getPlayer().getWorld();
        BlockPoint from = ai.getPosition();
        MemorySystem memory = ai.getMemory();
        List<MemoryEntry> lakes = memory.all(MemoryType.LAVA_LAKE, world.getUID(), ai.getTicks()).stream()
                .sorted(Comparator.comparingDouble(entry -> entry.pos().distance(from))).toList();
        for (MemoryEntry entry : lakes) {
            BlockPoint pos = entry.pos();
            if (pos.distance(from) > LAKE_RANGE) return null;
            // 청크가 내려가 있으면 확인할 수 없으니 이번에는 건너뛴다.
            if (!Positions.isLoaded(world, pos)) continue;
            if (isLakeSurface(world, pos)) return pos;
            memory.forget(MemoryType.LAVA_LAKE, world.getUID(), pos);
        }
        return null;
    }

    private static boolean isLavaSource(World world, BlockPoint cell) {
        if (cell.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight() || !Positions.isLoaded(world, cell)) return false;
        Block block = Positions.block(world, cell);
        return block.getType() == Material.LAVA && block.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0;
    }

    // 호수의 표면: 흐르지 않는 용암이거나, 물을 부어서 이미 굳힌 흑요석. 한 자리에서 다 못 캤으면 다른 자리에서 다시 물을 부어 마저 캔다.
    private static boolean isLakeSurface(World world, BlockPoint cell) {
        return isLavaSource(world, cell) || typeAt(world, cell) == Material.OBSIDIAN;
    }

    private static boolean isObsidianOrLava(World world, BlockPoint cell) {
        Material type = typeAt(world, cell);
        return type == Material.OBSIDIAN || type == Material.LAVA;
    }

    private static @Nullable Material typeAt(World world, BlockPoint cell) {
        if (cell.y() < world.getMinHeight() || cell.y() >= world.getMaxHeight() || !Positions.isLoaded(world, cell)) return null;
        return Positions.block(world, cell).getType();
    }
}
