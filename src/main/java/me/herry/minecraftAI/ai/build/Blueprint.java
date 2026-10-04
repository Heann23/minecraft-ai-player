package me.herry.minecraftAI.ai.build;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 건물 하나의 설계도. 기준점(건물 안에서 AI 가 서는 칸)에 대한 상대 좌표로 각 칸의 역할을 적어 둔 데이터다.
 * 목록의 순서가 곧 짓는 순서다: 블록은 이웃 블록에 붙여야 놓을 수 있으므로 아래에서 위로, 바깥에서 안으로 놓는다.
 * 새 건물을 추가하려면 Blueprints 에 이런 목록을 하나 더 만들면 된다. Bukkit 에 의존하지 않는다.
 */
public final class Blueprint {
    /**
     * @param dx,dy,dz 기준점에서의 상대 위치. dy = 0 이 서 있는 높이, -1 이 바닥이다.
     */
    public record Part(int dx, int dy, int dz, BlockRole role) {
        public BlockPoint at(BlockPoint origin) {
            return origin.offset(dx, dy, dz);
        }
    }

    private final String name;
    private final List<Part> parts;
    private final BlockPoint min;
    private final BlockPoint max;

    Blueprint(String name, List<Part> parts) {
        if (parts.isEmpty()) throw new IllegalArgumentException("blueprint has no parts");
        this.name = name;
        this.parts = List.copyOf(parts);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Part part : parts) {
            minX = Math.min(minX, part.dx());
            minY = Math.min(minY, part.dy());
            minZ = Math.min(minZ, part.dz());
            maxX = Math.max(maxX, part.dx());
            maxY = Math.max(maxY, part.dy());
            maxZ = Math.max(maxZ, part.dz());
        }
        this.min = new BlockPoint(minX, minY, minZ);
        this.max = new BlockPoint(maxX, maxY, maxZ);
    }

    public String name() {
        return name;
    }

    // 짓는 순서대로 놓인 칸 목록
    public List<Part> parts() {
        return parts;
    }

    // 건물이 차지하는 영역의 양 끝 (상대 좌표)
    public BlockPoint min() {
        return min;
    }

    public BlockPoint max() {
        return max;
    }

    public int count(BlockRole role) {
        int count = 0;
        for (Part part : parts) {
            if (part.role() == role) count++;
        }
        return count;
    }

    public List<Part> partsOf(BlockRole role) {
        List<Part> result = new ArrayList<>();
        for (Part part : parts) {
            if (part.role() == role) result.add(part);
        }
        return result;
    }
}
