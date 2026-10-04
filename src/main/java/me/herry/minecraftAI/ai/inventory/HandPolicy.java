package me.herry.minecraftAI.ai.inventory;

import java.util.List;

/**
 * 손에 무엇을 들지 정하는 규칙. 도구는 맞지 않는 일에 써도(곡괭이로 동물 때리기, 흙 캐기) 내구도가 줄기 때문에,
 * 도구가 도움이 되지 않는 일에는 들지 않는다. 아이템 이름만 보고 판단해서 서버 없이 테스트할 수 있다.
 */
public final class HandPolicy {
    public enum Purpose {
        // 몬스터와 싸운다. 검이나 도끼가 없으면 곡괭이라도 든다.
        FIGHT,
        // 달아나기만 하는 동물을 잡는다. 캐는 도구는 아껴 두고, 검이나 도끼가 없으면 맨손으로 잡는다.
        HUNT
    }

    private static final double SWORD_BONUS = 0.3;
    private static final double AXE_BONUS = 0.2;
    // 캐는 도구는 같은 재질의 검보다 약하다.
    private static final double MINING_TOOL_FACTOR = 0.7;

    private HandPolicy() {
    }

    /**
     * 그 아이템을 무기로 들었을 때의 상대적 공격력. 무기로 쓰지 않을 아이템이면 0.
     */
    public static double weaponPower(String itemName, Purpose purpose) {
        double tierPower = ToolTier.ofName(itemName).combatPower();
        if (itemName.endsWith("_SWORD")) return tierPower + SWORD_BONUS;
        if (isMiningTool(itemName)) return purpose == Purpose.HUNT ? 0.0 : tierPower * MINING_TOOL_FACTOR;
        if (itemName.endsWith("_AXE")) return tierPower + AXE_BONUS;
        return 0.0;
    }

    // 곡괭이와 삽. 이름이 "AXE" 로 끝나는 곡괭이를 도끼로 보지 않도록 도끼보다 먼저 확인한다.
    private static boolean isMiningTool(String itemName) {
        return itemName.endsWith("_PICKAXE") || itemName.endsWith("_SHOVEL");
    }

    /**
     * 블록을 캘 때 쓸 수 있는 도구 하나.
     *
     * @param speed    그 도구로 이 블록을 캐는 속도
     * @param tier     재질 등급 (ToolTier.level())
     * @param harvests 그 도구로 캤을 때 아이템이 나오는지
     */
    public record ToolOption(int slot, float speed, int tier, boolean harvests) {
    }

    // 이 등급까지는 재료(나무, 돌)를 어디서나 구할 수 있어서 아끼지 않고 쓴다.
    private static final int CHEAP_TIER = ToolTier.STONE.level();

    /**
     * 블록을 캘 도구를 고른다. 아이템이 나오는 도구 중에서 가장 싼 것을 쓴다.
     * 돌과 철 광석은 돌 곡괭이로 캐고, 철 곡괭이는 그것이 있어야 캘 수 있는 블록(다이아몬드 등)에만 쓴다.
     * 굴을 파는 돌마다 철 곡괭이를 쓰면 철을 들여 만든 곡괭이가 몇 분 만에 부서진다.
     *
     * @return 고른 도구의 칸. 맨손보다 빠른 도구가 없으면 -1.
     */
    public static int toolSlot(List<ToolOption> options, float handSpeed) {
        ToolOption best = null;
        for (ToolOption option : options) {
            if (option.speed() <= handSpeed) continue;
            if (best == null || isBetterTool(option, best)) best = option;
        }
        return best == null ? -1 : best.slot();
    }

    private static boolean isBetterTool(ToolOption a, ToolOption b) {
        if (a.harvests() != b.harvests()) return a.harvests();
        boolean aCheap = a.tier() <= CHEAP_TIER;
        boolean bCheap = b.tier() <= CHEAP_TIER;
        if (aCheap != bCheap) return aCheap;
        // 싼 도구끼리는 빠른 것을, 귀한 도구끼리는 낮은 등급을 쓴다.
        if (!aCheap && a.tier() != b.tier()) return a.tier() < b.tier();
        return a.speed() > b.speed();
    }

    /**
     * 더 좋은 곡괭이가 있어도 남겨 두는 "막 쓰는 곡괭이"인지. 가장 좋은 곡괭이가 철 이상일 때, 돌 이하의 곡괭이가 그것이다.
     *
     * @param level     이 곡괭이의 등급
     * @param bestLevel 가진 곡괭이 중 가장 좋은 등급
     */
    public static boolean isWorkPickaxe(int level, int bestLevel) {
        return level <= CHEAP_TIER && bestLevel > CHEAP_TIER;
    }

    // 나무/금 곡괭이는 비상용으로 남겨도, 새 돌 곡괭이의 보충을 막지 않는다.
    // 돌과 같은 등급의 구리 곡괭이도 일반 채굴에 계속 쓸 수 있다.
    public static boolean isAdequateWorkPickaxe(int level) {
        return level == CHEAP_TIER;
    }

    /**
     * 닳는 도구를 내려놓으려면 어느 칸을 들어야 하는지. 지금 든 것이 닳지 않으면 그대로 held 를 돌려준다.
     * 번호가 작은 칸부터 찾으므로 핫바가 먼저고, 핫바가 전부 도구면 가방의 칸이 나온다. 전부 닳는 아이템이면 -1.
     *
     * @param wearsOut 칸마다 닳는 아이템(도구, 무기)이 들어 있는지
     */
    public static int restSlot(boolean[] wearsOut, int held) {
        if (!wearsOut[held]) return held;
        for (int slot = 0; slot < wearsOut.length; slot++) {
            if (!wearsOut[slot]) return slot;
        }
        return -1;
    }
}
