package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.inventory.ArmorSlot;
import me.herry.minecraftAI.ai.inventory.ArmorTier;
import me.herry.minecraftAI.ai.inventory.ToolTier;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 진행 단계를 판정하는 데 필요한 사실만 모은 값 객체. Bukkit 없이 만들 수 있어서 진행 판정을 서버 없이 테스트할 수 있다.
 * 인벤토리에서 온 값(도구, 갑옷, 아이템 개수)과 월드에서 이룬 일(포탈, 요새 등)을 함께 담는다.
 */
public final class ProgressFacts {
    private static final int STONE_PER_PICKAXE = 3;
    private static final int STICKS_PER_PICKAXE = 2;
    private static final int PLANKS_FOR_STICKS = 2;

    public ToolTier pickaxe = ToolTier.NONE;
    public ToolTier axe = ToolTier.NONE;
    public ToolTier sword = ToolTier.NONE;
    // 가방에 있거나, 다시 찾아갈 만큼 가까운 곳에 설치되어 있는지
    public boolean tableAvailable;
    public boolean furnaceAvailable;
    // 집에 화로를 들여놓았는지. 집에서 멀리 나와 있어도 화로를 이미 가진 것으로 친다.
    public boolean furnaceAtHome;

    // 벽과 지붕이 다 지어진 집이 거점에 있는지
    public boolean shelterBuilt;
    public boolean netherPortalBuilt;
    public boolean strongholdFound;
    public boolean endPortalReady;
    public boolean dragonDefeated;

    private final Map<ArmorSlot, ArmorTier> armor = new EnumMap<>(ArmorSlot.class);
    // 계속 실패해서 한동안 미뤄 둔 항목 (없어도 되는 것만 미룰 수 있다)
    private final Set<Milestone> deferred = EnumSet.noneOf(Milestone.class);
    // Material 이름별 보유 개수 (가방 + 손)
    private final Map<String, Integer> items = new HashMap<>();

    // 입고 있거나 가지고 있는 것 중 그 부위의 가장 좋은 갑옷 등급
    public ArmorTier armor(ArmorSlot slot) {
        return armor.getOrDefault(slot, ArmorTier.NONE);
    }

    public void setArmor(ArmorSlot slot, ArmorTier tier) {
        armor.put(slot, tier);
    }

    public int count(String material) {
        return items.getOrDefault(material, 0);
    }

    public boolean has(String material) {
        return count(material) > 0;
    }

    public void setCount(String material, int count) {
        items.put(material, count);
    }

    // 이름이 suffix 로 끝나는 아이템의 개수 합 (예: "_PLANKS" 면 모든 종류의 판자)
    public int countEndingWith(String suffix) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (entry.getKey().endsWith(suffix)) total += entry.getValue();
        }
        return total;
    }

    /**
     * 가진 것만으로 돌 곡괭이를 바로 만들 수 있는지 (돌 3개와 막대 2개, 막대는 판자 2개로 만들 수 있다).
     * 곡괭이가 부서졌을 때 나무 곡괭이부터 다시 만들지 않고 돌 곡괭이로 건너뛰는 데 쓴다.
     */
    public boolean canMakeStonePickaxe() {
        int stone = count("COBBLESTONE") + count("COBBLED_DEEPSLATE") + count("BLACKSTONE");
        return stone >= STONE_PER_PICKAXE && (count("STICK") >= STICKS_PER_PICKAXE || countEndingWith("_PLANKS") >= PLANKS_FOR_STICKS);
    }

    public void defer(Milestone milestone) {
        if (milestone.isOptional()) deferred.add(milestone);
    }

    public boolean isDeferred(Milestone milestone) {
        return deferred.contains(milestone);
    }
}
