package me.herry.minecraftAI.ai.navigation;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.HashMap;
import java.util.Map;

/**
 * 테스트용 지형. 기본적으로 y < 64 는 땅, 그 위는 공기인 평지이며 원하는 칸만 바꿔 넣을 수 있다.
 */
final class GridTerrain implements TerrainView {
    static final int GROUND = 64;

    private final Map<BlockPoint, BlockClass> overrides = new HashMap<>();
    int lookups;

    GridTerrain set(int x, int y, int z, BlockClass blockClass) {
        overrides.put(new BlockPoint(x, y, z), blockClass);
        return this;
    }

    // (x, z) 기둥을 fromY 부터 toY 까지 같은 종류로 채운다.
    GridTerrain column(int x, int z, int fromY, int toY, BlockClass blockClass) {
        for (int y = fromY; y <= toY; y++) set(x, y, z, blockClass);
        return this;
    }

    @Override
    public BlockClass classify(int x, int y, int z) {
        lookups++;
        BlockClass override = overrides.get(new BlockPoint(x, y, z));
        if (override != null) return override;
        return y < GROUND ? BlockClass.SOLID : BlockClass.OPEN;
    }
}
