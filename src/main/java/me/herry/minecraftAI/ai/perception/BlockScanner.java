package me.herry.minecraftAI.ai.perception;

import me.herry.minecraftAI.ai.memory.MemorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.navigation.BlockClass;
import me.herry.minecraftAI.ai.navigation.BukkitTerrainView;
import me.herry.minecraftAI.ai.perf.WorkBudget;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.config.AIConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.loot.Lootable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 주변 블록을 훑어서 나무, 돌, 광물, 작업대 같은 관심 블록의 위치를 기억에 올린다.
 * 범위 전체를 한 틱에 검사하면 서버가 버벅이므로, 가까운 블록부터 한 틱에 정해진 개수만큼만 검사한다.
 */
public final class BlockScanner {
    // 자원 블록은 캐 가거나 바뀔 수 있어서 5분 뒤에 잊는다. 다시 훑으면 기억이 갱신된다.
    private static final long RESOURCE_TTL = 6000L;
    // 한 번 훑을 때 종류별로 기억할 최대 개수. 가까운 순서로 훑으므로 가까운 것만 남는다.
    private static final int MAX_PER_TYPE = 24;
    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static final Map<Material, MemoryType> INTEREST = new EnumMap<>(Material.class);
    private static final Map<Material, Boolean> INTEREST_KNOWN = new EnumMap<>(Material.class);

    // 이만큼 검사할 때마다 시간 예산이 남았는지 확인한다.
    private static final int BUDGET_CHECK_INTERVAL = 64;

    private final AIConfig config;
    private final WorkBudget budget;
    private final int[] offsets;
    private final Map<MemoryType, Integer> foundPerType = new EnumMap<>(MemoryType.class);

    private World world;
    private BlockPoint center;
    // 검색을 시작할 때의 눈 위치. 이 자리에서 보이는 블록만 기억한다.
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private Visibility.Opacity opacity;
    private int cursor;
    private boolean running;
    private long lastFinishedTick = Long.MIN_VALUE / 2;
    private int found;
    private int lastFound;

    public BlockScanner(AIConfig config, WorkBudget budget) {
        this.config = config;
        this.budget = budget;
        this.offsets = buildOffsets(config.blockRange, config.blockVerticalRange);
    }

    /**
     * 검색을 한 틱 분량만큼 진행한다. 검색 한 바퀴가 끝난 틱에만 true 를 반환한다.
     */
    public boolean tick(Player player, MemorySystem memory, long now) {
        if (!running) {
            if (!shouldStart(player, now)) return false;
            start(player);
        }
        // 검색 도중 월드가 바뀌면 좌표가 의미를 잃으므로 처음부터 다시 한다.
        if (!player.getWorld().equals(world)) {
            running = false;
            return false;
        }

        int end = Math.min(cursor + config.blocksPerTick * 3, offsets.length);
        int inspected = 0;
        while (cursor < end) {
            inspect(center.x() + offsets[cursor], center.y() + offsets[cursor + 1], center.z() + offsets[cursor + 2], memory, now);
            cursor += 3;
            // 이번 서버 틱의 시간 예산을 다 썼으면 남은 블록은 다음 틱에 본다. 그래도 한 묶음은 항상 진행해서 검색이 멈추지는 않게 한다.
            if (++inspected % BUDGET_CHECK_INTERVAL == 0 && budget.isExhausted()) break;
        }
        if (cursor < offsets.length) return false;

        running = false;
        lastFinishedTick = now;
        lastFound = found;
        return true;
    }

    public void reset() {
        running = false;
        center = null;
        world = null;
        opacity = null;
    }

    public int getLastFound() {
        return lastFound;
    }

    private boolean shouldStart(Player player, long now) {
        if (center == null || !player.getWorld().equals(world)) return true;
        if (now - lastFinishedTick >= config.rescanInterval) return true;
        // 검색했던 범위의 절반 이상을 벗어나면 새 지역이므로 다시 훑는다.
        Location location = player.getLocation();
        double moved = Positions.of(location).distance(center);
        return moved > config.blockRange / 2.0;
    }

    private void start(Player player) {
        world = player.getWorld();
        center = Positions.of(player.getLocation());
        Location eye = player.getEyeLocation();
        eyeX = eye.getX();
        eyeY = eye.getY();
        eyeZ = eye.getZ();
        opacity = PerceptionSystem.opacityOf(world);
        cursor = 0;
        found = 0;
        foundPerType.clear();
        running = true;
    }

    private void inspect(int x, int y, int z, MemorySystem memory, long now) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;

        Material material = world.getBlockAt(x, y, z).getType();
        if (material.isAir()) return;
        MemoryType type = interestOf(material);
        if (type == null) return;

        int count = foundPerType.getOrDefault(type, 0);
        if (count >= MAX_PER_TYPE) return;
        // 상자는 전리품이 아직 들어 있는 것(구조물의 상자)만 기억한다. 누군가 놓은 일반 상자는 건드리지 않는다.
        if (type == MemoryType.LOOT_CHEST && !isUnopenedLootChest(world.getBlockAt(x, y, z))) return;
        // 돌과 광물은 겉으로 드러난 것만 기억한다. 땅속에 묻힌 블록은 걸어서 닿을 수 없다.
        if (needsExposure(type) && !isExposed(x, y, z)) return;
        // 실제 플레이어처럼, 빛이 통과하지 않는 블록 너머(벽 뒤의 다른 동굴 등)에 있는 것은 보이지 않는다.
        if (!Visibility.canSeeBlock(opacity, eyeX, eyeY, eyeZ, x, y, z)) return;

        foundPerType.put(type, count + 1);
        found++;
        long ttl = isResource(type) || type == MemoryType.DANGER_PLACE ? RESOURCE_TTL : -1L;
        memory.remember(type, world.getUID(), new BlockPoint(x, y, z), now, ttl);
    }

    /**
     * 캐서 빈칸이 된 opened 의 바로 옆에 있는 광석 중 눈에 보이는 것을 기억한다.
     * 광석을 하나 캐면 그 뒤에 붙어 있던 광석이 드러나므로, 이것으로 광맥 전체를 따라가며 캔다.
     */
    static void noticeOpened(Player player, MemorySystem memory, BlockPoint opened, long now) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Visibility.Opacity opacity = PerceptionSystem.opacityOf(world);
        for (int[] neighbor : NEIGHBORS) {
            BlockPoint point = opened.offset(neighbor[0], neighbor[1], neighbor[2]);
            if (point.y() < world.getMinHeight() || point.y() >= world.getMaxHeight() || !Positions.isLoaded(world, point)) continue;
            MemoryType type = interestOf(Positions.block(world, point).getType());
            if (type != MemoryType.COAL_ORE && type != MemoryType.IRON_ORE && type != MemoryType.DIAMOND_ORE) continue;
            if (!Visibility.canSeeBlock(opacity, eye.getX(), eye.getY(), eye.getZ(), point.x(), point.y(), point.z())) continue;
            memory.remember(type, world.getUID(), point, now, RESOURCE_TTL);
        }
    }

    public static boolean isUnopenedLootChest(Block block) {
        return block.getState(false) instanceof Lootable lootable && lootable.hasLootTable();
    }

    private boolean isExposed(int x, int y, int z) {
        for (int[] neighbor : NEIGHBORS) {
            int nx = x + neighbor[0];
            int ny = y + neighbor[1];
            int nz = z + neighbor[2];
            if (ny < world.getMinHeight() || ny >= world.getMaxHeight() || !world.isChunkLoaded(nx >> 4, nz >> 4)) continue;
            BlockClass blockClass = BukkitTerrainView.classify(world.getBlockAt(nx, ny, nz).getType());
            if (blockClass == BlockClass.OPEN || blockClass == BlockClass.WATER) return true;
        }
        return false;
    }

    private static boolean needsExposure(MemoryType type) {
        return type == MemoryType.STONE || type == MemoryType.COAL_ORE || type == MemoryType.IRON_ORE || type == MemoryType.DIAMOND_ORE;
    }

    private static boolean isResource(MemoryType type) {
        return type == MemoryType.TREE || needsExposure(type);
    }

    // 이 블록이 기억할 만한 블록이면 그 종류를, 아니면 null 을 돌려준다.
    public static @Nullable MemoryType interestOf(Material material) {
        if (INTEREST_KNOWN.containsKey(material)) return INTEREST.get(material);
        MemoryType type = classifyInterest(material);
        INTEREST_KNOWN.put(material, Boolean.TRUE);
        if (type != null) INTEREST.put(material, type);
        return type;
    }

    private static MemoryType classifyInterest(Material material) {
        if (Tag.LOGS.isTagged(material)) return MemoryType.TREE;
        if (Tag.COAL_ORES.isTagged(material)) return MemoryType.COAL_ORE;
        if (Tag.IRON_ORES.isTagged(material)) return MemoryType.IRON_ORE;
        if (Tag.DIAMOND_ORES.isTagged(material)) return MemoryType.DIAMOND_ORE;
        if (Tag.BEDS.isTagged(material)) return MemoryType.BED;
        return switch (material) {
            case STONE, COBBLESTONE, DEEPSLATE, COBBLED_DEEPSLATE, BLACKSTONE -> MemoryType.STONE;
            case CRAFTING_TABLE -> MemoryType.WORKBENCH;
            case FURNACE -> MemoryType.FURNACE;
            case LAVA -> MemoryType.DANGER_PLACE;
            case CHEST, BARREL -> MemoryType.LOOT_CHEST;
            default -> null;
        };
    }

    // 중심에서 가까운 순서로 정렬한 상대 좌표 목록. (dx, dy, dz) 세 개씩 이어 붙인 배열이다.
    private static int[] buildOffsets(int range, int verticalRange) {
        List<int[]> list = new ArrayList<>();
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -verticalRange; dy <= verticalRange; dy++) {
                    list.add(new int[]{dx, dy, dz});
                }
            }
        }
        list.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));

        int[] result = new int[list.size() * 3];
        for (int i = 0; i < list.size(); i++) {
            int[] offset = list.get(i);
            result[i * 3] = offset[0];
            result[i * 3 + 1] = offset[1];
            result[i * 3 + 2] = offset[2];
        }
        return result;
    }
}
