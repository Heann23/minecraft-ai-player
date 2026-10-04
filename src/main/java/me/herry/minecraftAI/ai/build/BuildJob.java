package me.herry.minecraftAI.ai.build;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 짓고 있는 건물 하나: 어느 월드의 어디에(기준점) 어떤 설계도로 짓는지.
 * 진행 상황을 따로 세지 않고, 그때그때 실제 월드를 보고 "아직 안 된 칸"을 계산한다. 그래서 중간에 재료를 구하러
 * 다녀오거나 서버가 재시작되어도, 누가 블록을 부수거나 대신 놓아 주어도 이어서 지을 수 있다.
 * Bukkit 에 의존하지 않으며, 월드를 보는 일은 호출하는 쪽이 넘겨주는 판정 함수가 한다.
 */
public final class BuildJob {
    private final UUID world;
    private final BlockPoint origin;
    private final Blueprint blueprint;

    public BuildJob(UUID world, BlockPoint origin, Blueprint blueprint) {
        this.world = world;
        this.origin = origin;
        this.blueprint = blueprint;
    }

    public UUID world() {
        return world;
    }

    // 건물 안에서 AI 가 서는 칸
    public BlockPoint origin() {
        return origin;
    }

    public Blueprint blueprint() {
        return blueprint;
    }

    // 건물이 차지하는 영역의 양 끝 (월드 좌표)
    public BlockPoint min() {
        return origin.offset(blueprint.min().x(), blueprint.min().y(), blueprint.min().z());
    }

    public BlockPoint max() {
        return origin.offset(blueprint.max().x(), blueprint.max().y(), blueprint.max().z());
    }

    /**
     * 아직 끝나지 않은 칸을 짓는 순서대로 돌려준다.
     *
     * @param done 그 칸이 이미 설계도대로 되어 있는지 알려 주는 함수
     */
    public List<Blueprint.Part> pending(Predicate<Blueprint.Part> done) {
        List<Blueprint.Part> pending = new ArrayList<>();
        for (Blueprint.Part part : blueprint.parts()) {
            if (!done.test(part)) pending.add(part);
        }
        return pending;
    }

    // 아직 놓지 않은 벽, 지붕, 바닥 블록의 수. 필요한 재료의 양을 셀 때 쓴다.
    public int pendingStructural(Predicate<Blueprint.Part> done) {
        int count = 0;
        for (Blueprint.Part part : blueprint.parts()) {
            if (part.role().isStructural() && !done.test(part)) count++;
        }
        return count;
    }

    // 꼭 있어야 하는 칸(비우기, 바닥, 벽, 지붕, 문, 작업대, 상자)이 모두 끝났는지. 화로, 횃불, 침대는 없어도 완성으로 친다.
    public boolean isComplete(Predicate<Blueprint.Part> done) {
        for (Blueprint.Part part : blueprint.parts()) {
            boolean mustBeDone = part.role().isRequired() || part.role() == BlockRole.CLEAR;
            if (mustBeDone && !done.test(part)) return false;
        }
        return true;
    }

    public Map<String, Object> exportState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("world", world.toString());
        state.put("origin", origin.encode());
        state.put("blueprint", blueprint.name());
        return state;
    }

    // 저장된 내용을 읽을 수 없거나 설계도가 없어졌으면 null.
    public static @Nullable BuildJob importState(Map<String, Object> state) {
        try {
            Blueprint blueprint = Blueprints.byName(String.valueOf(state.get("blueprint")));
            if (blueprint == null) return null;
            return new BuildJob(UUID.fromString(String.valueOf(state.get("world"))), BlockPoint.parse(String.valueOf(state.get("origin"))), blueprint);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
