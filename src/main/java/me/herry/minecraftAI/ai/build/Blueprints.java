package me.herry.minecraftAI.ai.build;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 가 지을 수 있는 건물의 설계도 모음. 지금은 기본 집 하나뿐이다.
 * 창고, 광산 입구, 네더 포탈 방 같은 건물은 여기에 설계도를 더하고 지을 조건(목표)을 등록하면 된다.
 */
public final class Blueprints {
    public static final String SHELTER = "shelter";

    public static final String PORTAL = "portal";
    private static final Blueprint SHELTER_BLUEPRINT = buildShelter();
    private static final Blueprint PORTAL_BLUEPRINT = buildPortal();

    private Blueprints() {
    }

    public static Blueprint shelter() {
        return SHELTER_BLUEPRINT;
    }

    // 저장 파일에 적힌 이름으로 설계도를 찾는다. 없어진 설계도면 null.
    public static @Nullable Blueprint byName(String name) {
        if (PORTAL.equals(name)) return PORTAL_BLUEPRINT;
        return SHELTER.equals(name) ? SHELTER_BLUEPRINT : null;
    }

    public static Blueprint portal() {
        return PORTAL_BLUEPRINT;
    }

    /**
     * 네더 포탈의 틀. 기준점(AI 가 서는 칸)의 한 칸 북쪽(-Z)에 동서로 선다.
     * 모서리를 뺀 4x5 틀은 흑요석 10개다. 모서리 네 칸은 흔한 블록으로 채운다: 옆 기둥과 윗줄은 맞닿은 블록이 있어야
     * 놓을 수 있는데 모서리가 그 받침이 된다.
     *
     * <pre>
     *   앞에서 본 모습 (O 흑요석, c 모서리의 흔한 블록, . 비워야 하는 칸)     위에서 본 모습
     *     dy=4   c  O  O  c                                               dz=-1   c O O c     ← 틀
     *     dy=3   O  .  .  O                                               dz= 0   . @ . .     ← AI 가 서는 기준점
     *     dy=2   O  .  .  O
     *     dy=1   O  .  .  O
     *     dy=0   c  O  O  c      ← 땅 위에 놓는다 (dy=0 이 서 있는 높이)
     *          dx=-1  0  1  2
     * </pre>
     *
     * 기준점에서 가장 먼 칸(윗줄 모서리)이 눈에서 3.7칸이라 한자리에서 다 지을 수 있다.
     */
    private static Blueprint buildPortal() {
        final int left = -1;
        final int right = 2;
        final int top = 4;
        final int frameZ = -1;
        List<Blueprint.Part> parts = new ArrayList<>();
        // 1. 틀 안쪽과 서 있을 자리를 비운다.
        for (int dy = 1; dy < top; dy++) {
            for (int dx = left + 1; dx < right; dx++) parts.add(new Blueprint.Part(dx, dy, frameZ, BlockRole.CLEAR));
        }
        for (int dy = 0; dy <= 1; dy++) parts.add(new Blueprint.Part(0, dy, 0, BlockRole.CLEAR));
        // 2. 틀과 서 있을 자리의 바닥.
        for (int dx = left; dx <= right; dx++) {
            parts.add(new Blueprint.Part(dx, -1, frameZ, BlockRole.FLOOR));
            parts.add(new Blueprint.Part(dx, -1, 0, BlockRole.FLOOR));
        }
        // 3. 아랫줄, 양쪽 기둥을 한 층씩, 윗줄(모서리 먼저) 순서로 놓는다. 블록은 먼저 놓인 이웃에 붙는다.
        for (int dx = left; dx <= right; dx++) parts.add(new Blueprint.Part(dx, 0, frameZ, roleAt(dx, left, right)));
        for (int dy = 1; dy < top; dy++) {
            parts.add(new Blueprint.Part(left, dy, frameZ, BlockRole.FRAME));
            parts.add(new Blueprint.Part(right, dy, frameZ, BlockRole.FRAME));
        }
        parts.add(new Blueprint.Part(left, top, frameZ, BlockRole.WALL));
        parts.add(new Blueprint.Part(right, top, frameZ, BlockRole.WALL));
        for (int dx = left + 1; dx < right; dx++) parts.add(new Blueprint.Part(dx, top, frameZ, BlockRole.FRAME));
        return new Blueprint(PORTAL, parts);
    }

    private static BlockRole roleAt(int dx, int left, int right) {
        return dx == left || dx == right ? BlockRole.WALL : BlockRole.FRAME;
    }

    /**
     * 실용적인 첫 집. 바깥 5x5, 안 3x3, 안쪽 높이 2칸, 평평한 지붕, 남쪽(+Z) 벽 가운데에 문.
     *
     * <pre>
     *   위에서 본 모습 (# 벽, D 문, W 작업대, F 화로, C 상자, B 침대, T 횃불, @ AI 가 서는 기준점)
     *        x=-2 -1  0  1  2
     *   z=-2   #  #  #  #  #
     *   z=-1   #  W  .  F  #
     *   z= 0   #  C  @  B  #
     *   z= 1   #  T  .  B  #
     *   z= 2   #  #  D  #  #
     * </pre>
     *
     * 기준점에 서면 모든 칸이 손이 닿는 거리 안이라, 한자리에서 집 전체를 지을 수 있다.
     */
    private static Blueprint buildShelter() {
        final int outer = 2;
        final int inner = 1;
        final int wallHeight = 2;
        List<Blueprint.Part> parts = new ArrayList<>();

        // 1. 실내와 문 자리를 비운다. 벽 자리에 이미 흙 같은 블록이 있으면 그대로 벽으로 쓴다.
        for (int dy = 0; dy < wallHeight; dy++) {
            for (int dx = -inner; dx <= inner; dx++) {
                for (int dz = -inner; dz <= inner; dz++) parts.add(new Blueprint.Part(dx, dy, dz, BlockRole.CLEAR));
            }
            parts.add(new Blueprint.Part(0, dy, outer, BlockRole.CLEAR));
        }
        // 2. 바닥의 구멍을 메운다.
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) parts.add(new Blueprint.Part(dx, -1, dz, BlockRole.FLOOR));
        }
        // 3. 벽을 한 층씩 올린다. 문 자리(남쪽 벽 가운데)는 비워 둔다.
        for (int dy = 0; dy < wallHeight; dy++) {
            for (int dx = -outer; dx <= outer; dx++) {
                for (int dz = -outer; dz <= outer; dz++) {
                    boolean edge = Math.abs(dx) == outer || Math.abs(dz) == outer;
                    boolean doorway = dx == 0 && dz == outer;
                    if (edge && !doorway) parts.add(new Blueprint.Part(dx, dy, dz, BlockRole.WALL));
                }
            }
        }
        // 4. 지붕은 벽 위의 바깥 고리부터 놓고 안쪽으로 메워 간다. 안쪽 블록은 먼저 놓인 이웃 지붕에 붙는다.
        for (int ring = outer; ring >= 0; ring--) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) parts.add(new Blueprint.Part(dx, wallHeight, dz, BlockRole.ROOF));
                }
            }
        }
        // 5. 시설. 작업대를 먼저 놓아야 그 자리에서 문과 상자를 만들 수 있다.
        parts.add(new Blueprint.Part(-1, 0, -1, BlockRole.WORKBENCH));
        parts.add(new Blueprint.Part(-1, 0, 0, BlockRole.CHEST));
        parts.add(new Blueprint.Part(1, 0, -1, BlockRole.FURNACE));
        parts.add(new Blueprint.Part(-1, 0, 1, BlockRole.TORCH));
        // 침대는 발치 칸만 적는다. 머리 칸은 남쪽(+Z)으로 한 칸 옆이다.
        parts.add(new Blueprint.Part(1, 0, 0, BlockRole.BED));
        // 6. 문은 마지막에 단다.
        parts.add(new Blueprint.Part(0, 0, outer, BlockRole.DOOR));
        return new Blueprint(SHELTER, parts);
    }
}
