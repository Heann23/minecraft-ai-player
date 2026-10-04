package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.util.EnumMap;
import java.util.Map;

/**
 * 실제 월드의 블록을 경로 탐색용 분류로 바꿔 준다.
 * 탐색 한 번 동안 같은 블록을 여러 번 묻기 때문에 결과를 기억해 둔다. 메인 스레드에서만 써야 한다.
 */
public final class BukkitTerrainView implements TerrainView {
    // Material 별 분류는 바뀌지 않으므로 모든 인스턴스가 공유한다.
    private static final Map<Material, BlockClass> MATERIAL_CLASSES = new EnumMap<>(Material.class);
    private static final BlockClass[] CLASSES = BlockClass.values();
    private static final byte NOT_CACHED = -1;

    private final World world;
    private final int minHeight;
    private final int maxHeight;
    // 탐색 중에 초당 수십만 번 조회되므로 Long 객체를 만들지 않는 맵을 쓴다. 값은 BlockClass 의 순서 번호다.
    private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();

    public BukkitTerrainView(World world) {
        this.world = world;
        this.minHeight = world.getMinHeight();
        this.maxHeight = world.getMaxHeight();
        this.cache.defaultReturnValue(NOT_CACHED);
    }

    @Override
    public BlockClass classify(int x, int y, int z) {
        // 월드 바닥 아래는 공허라서 떨어지면 죽는다.
        if (y < minHeight) return BlockClass.DANGER;
        if (y >= maxHeight) return BlockClass.OPEN;

        long key = BlockPoint.pack(x, y, z);
        byte cached = cache.get(key);
        if (cached != NOT_CACHED) return CLASSES[cached];

        BlockClass result;
        // 로드되지 않은 청크의 블록을 읽으면 청크가 동기 로드되어 서버가 멈출 수 있다.
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            result = BlockClass.UNLOADED;
        } else {
            result = classify(world.getBlockAt(x, y, z).getType());
        }
        cache.put(key, (byte) result.ordinal());
        return result;
    }

    public void clearCache() {
        cache.clear();
    }

    public static BlockClass classify(Material material) {
        return MATERIAL_CLASSES.computeIfAbsent(material, BukkitTerrainView::classifyUncached);
    }

    private static BlockClass classifyUncached(Material material) {
        if (material.isAir()) return BlockClass.OPEN;

        switch (material) {
            case WATER, BUBBLE_COLUMN, KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS -> {
                return BlockClass.WATER;
            }
            case LAVA, FIRE, SOUL_FIRE, CACTUS, MAGMA_BLOCK, SWEET_BERRY_BUSH, WITHER_ROSE,
                 POWDER_SNOW, CAMPFIRE, SOUL_CAMPFIRE, COBWEB -> {
                return BlockClass.DANGER;
            }
            default -> {
            }
        }

        // 눈이나 카펫은 충돌 판정은 있지만 높이가 거의 없어서 그 칸 안에 서게 된다.
        // 단단한 블록으로 보면 경로의 높이가 실제보다 한 칸 높게 계산된다.
        if (material == Material.SNOW || material == Material.MOSS_CARPET || material == Material.PALE_MOSS_CARPET
                || Tag.WOOL_CARPETS.isTagged(material)) {
            return BlockClass.OPEN;
        }

        if (Tag.FENCES.isTagged(material) || Tag.WALLS.isTagged(material) || Tag.FENCE_GATES.isTagged(material)) {
            return BlockClass.FENCE;
        }
        // 나무 문은 손으로 열 수 있으므로 지나갈 수 있는 칸으로 본다. 걸어가다가 닫힌 문을 만나면 NavigationSystem 이 연다.
        // 철문은 손으로 열 수 없어서 벽으로 남는다.
        if (Tag.WOODEN_DOORS.isTagged(material)) return BlockClass.OPEN;
        return material.isCollidable() ? BlockClass.SOLID : BlockClass.OPEN;
    }
}
