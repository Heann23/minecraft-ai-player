package me.herry.minecraftAI.ai.crafting;

import me.herry.minecraftAI.ai.util.BlockPoint;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 화로에 넣어 두고 온 것의 기록. 넣은 뒤에는 화로 앞에 서서 기다리지 않고 다른 일을 하다가, 다 구워지면 돌아와서 꺼낸다.
 * Bukkit 에 의존하지 않는다.
 *
 * @param food    굽는 것이 음식인지. 배가 고플 때는 다 구워지기를 기다렸다가 꺼내 먹는다.
 * @param count   넣은 개수
 * @param readyAt 다 구워질 것으로 보는 시각 (AI 틱)
 */
public record FurnaceJob(UUID world, BlockPoint pos, boolean food, int count, long readyAt) {
    // 아이템 하나를 굽는 데 걸리는 시간 (10초)
    public static final int TICKS_PER_ITEM = 200;
    // 다 구워졌어야 할 시각을 이만큼 넘겼는데도 재료가 남아 있으면 화로가 멈춘 것이다 (연료가 떨어졌거나 누가 빼 갔다).
    // 더 기다리지 않고 남은 것을 꺼낸다.
    public static final long OVERDUE_TICKS = 200L;
    // 넣어 둔 화로까지 길을 낼 수 없는 채로 이만큼(2분) 지나면 포기한다. 그 전에는 조금 뒤에, 옮겨 간 자리에서 다시 해 본다.
    public static final long GIVE_UP_TICKS = 2400L;

    public static FurnaceJob start(UUID world, BlockPoint pos, boolean food, int count, long now) {
        return new FurnaceJob(world, pos, food, count, now + (long) count * TICKS_PER_ITEM);
    }

    public boolean isAt(UUID otherWorld, BlockPoint otherPos) {
        return world.equals(otherWorld) && pos.equals(otherPos);
    }

    public boolean isOverdue(long now) {
        return now >= readyAt + OVERDUE_TICKS;
    }

    /**
     * 화로까지 길을 낼 수 없을 때 넣어 둔 것을 포기할지. 한 번 막혔다고 바로 포기하지 않는다.
     * 몬스터를 피해 숨은 직후나 물가처럼 그 자리에서만 팔 수 없는 경우가 많다.
     *
     * @param blockedSince 길을 낼 수 없게 된 시각 (AI 틱). 막힌 적이 없으면 음수.
     */
    public static boolean shouldGiveUp(long blockedSince, long now) {
        return blockedSince >= 0 && now - blockedSince >= GIVE_UP_TICKS;
    }

    public Map<String, Object> exportState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("world", world.toString());
        state.put("pos", pos.encode());
        state.put("food", food);
        state.put("count", count);
        state.put("readyAt", readyAt);
        return state;
    }

    // 저장된 내용을 읽을 수 없으면 null.
    public static @Nullable FurnaceJob importState(Map<String, Object> state) {
        try {
            UUID world = UUID.fromString(String.valueOf(state.get("world")));
            BlockPoint pos = BlockPoint.parse(String.valueOf(state.get("pos")));
            boolean food = Boolean.parseBoolean(String.valueOf(state.get("food")));
            int count = (int) Double.parseDouble(String.valueOf(state.get("count")));
            long readyAt = (long) Double.parseDouble(String.valueOf(state.get("readyAt")));
            return new FurnaceJob(world, pos, food, count, readyAt);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
