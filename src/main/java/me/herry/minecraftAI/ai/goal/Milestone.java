package me.herry.minecraftAI.ai.goal;

import me.herry.minecraftAI.ai.inventory.ArmorSlot;
import me.herry.minecraftAI.ai.inventory.ArmorTier;
import me.herry.minecraftAI.ai.inventory.ToolTier;

import java.util.function.Predicate;

/**
 * 엔더 드래곤을 잡을 때까지 순서대로 이뤄야 하는 일. AI 의 "중기 목표"에 해당한다.
 * 앞의 것이 있어야 뒤의 것을 할 수 있고, 장비를 잃으면(사망 등) 잃은 지점부터 다시 진행한다.
 * 각 항목은 "이미 이뤘는지"를 ProgressFacts 만 보고 판정한다.
 */
public enum Milestone {
    CRAFTING_TABLE(Stage.EARLY_SURVIVAL, Kind.CRAFT, Cost.NONE, "작업대", f -> f.tableAvailable),
    // 곡괭이가 부서졌더라도 돌 곡괭이를 바로 만들 재료가 있으면 나무 곡괭이는 건너뛴다.
    WOODEN_PICKAXE(Stage.EARLY_SURVIVAL, Kind.CRAFT, Cost.NONE, "나무 곡괭이",
            f -> f.pickaxe.isAtLeast(ToolTier.WOOD) || f.canMakeStonePickaxe()),
    STONE_PICKAXE(Stage.EARLY_SURVIVAL, Kind.CRAFT, Cost.STONE, "돌 곡괭이", f -> f.pickaxe.isAtLeast(ToolTier.STONE)),
    STONE_AXE(Stage.EARLY_SURVIVAL, Kind.CRAFT, Cost.STONE, "돌 도끼", f -> f.axe.isAtLeast(ToolTier.STONE)),
    STONE_SWORD(Stage.EARLY_SURVIVAL, Kind.CRAFT, Cost.STONE, "돌 검", f -> f.sword.isAtLeast(ToolTier.STONE)),
    FURNACE(Stage.EARLY_SURVIVAL, Kind.CRAFT, Cost.STONE, "화로", f -> f.furnaceAvailable || f.furnaceAtHome),
    // 땅속으로 내려가기 전에 돌아올 집(벽, 지붕, 문, 상자)을 먼저 짓는다.
    SHELTER(Stage.EARLY_SURVIVAL, Kind.WORLD, Cost.NONE, "집", f -> f.shelterBuilt),

    IRON_PICKAXE(Stage.IRON_AGE, Kind.CRAFT, Cost.iron(3), "철 곡괭이", f -> f.pickaxe.isAtLeast(ToolTier.IRON)),
    IRON_SWORD(Stage.IRON_AGE, Kind.CRAFT, Cost.iron(2), "철 검", f -> f.sword.isAtLeast(ToolTier.IRON)),
    // 방패는 철 하나로 만들고 화살과 크리퍼의 폭발을 막아 준다. 철이 많이 드는 갑옷보다 먼저 만든다.
    SHIELD(Stage.IRON_AGE, Kind.CRAFT, Cost.iron(1), "방패", f -> f.has("SHIELD")),
    // 갑옷은 "그 부위에 무엇이든 있는가"가 아니라 철 이상인지를 본다. 가죽 갑옷을 주웠다고 철 갑옷을 건너뛰면 안 된다.
    IRON_CHESTPLATE(Stage.IRON_AGE, Kind.CRAFT, Cost.iron(8), "철 흉갑", f -> f.armor(ArmorSlot.CHEST).isAtLeast(ArmorTier.IRON)),
    IRON_LEGGINGS(Stage.IRON_AGE, Kind.CRAFT, Cost.iron(7), "철 레깅스", f -> f.armor(ArmorSlot.LEGS).isAtLeast(ArmorTier.IRON)),
    IRON_HELMET(Stage.IRON_AGE, Kind.CRAFT, Cost.iron(5), "철 투구", f -> f.armor(ArmorSlot.HEAD).isAtLeast(ArmorTier.IRON)),
    IRON_BOOTS(Stage.IRON_AGE, Kind.CRAFT, Cost.iron(4), "철 부츠", f -> f.armor(ArmorSlot.FEET).isAtLeast(ArmorTier.IRON)),
    BUCKET(Stage.IRON_AGE, Kind.CRAFT, Cost.iron(3), "양동이",
            f -> f.has("BUCKET") || f.has("WATER_BUCKET") || f.has("LAVA_BUCKET")),

    DIAMOND_PICKAXE(Stage.DIAMOND_AGE, Kind.CRAFT, Cost.diamond(3), "다이아몬드 곡괭이", f -> f.pickaxe.isAtLeast(ToolTier.DIAMOND)),

    FLINT_AND_STEEL(Stage.NETHER_ENTRY, Kind.CRAFT, Cost.iron(1), "부싯돌과 부시", f -> f.netherPortalBuilt || f.has("FLINT_AND_STEEL")),
    OBSIDIAN(Stage.NETHER_ENTRY, Kind.GATHER, Cost.NONE, "흑요석 10개",
            f -> f.netherPortalBuilt || f.count("OBSIDIAN") >= Milestone.PORTAL_OBSIDIAN),
    NETHER_PORTAL(Stage.NETHER_ENTRY, Kind.WORLD, Cost.NONE, "네더 포탈", f -> f.netherPortalBuilt),

    // 엔더의 눈 하나에 블레이즈 가루 하나(막대 반 개)와 엔더 진주 하나가 든다. 이미 만든 눈은 재료를 구한 것으로 친다.
    BLAZE_RODS(Stage.NETHER, Kind.GATHER, Cost.NONE, "블레이즈 막대",
            f -> f.count("BLAZE_ROD") * 2 + f.count("BLAZE_POWDER") + f.count("ENDER_EYE") >= Milestone.EYES_NEEDED || f.endPortalReady),
    ENDER_PEARLS(Stage.ENDER_PEARLS, Kind.GATHER, Cost.NONE, "엔더 진주",
            f -> f.count("ENDER_PEARL") + f.count("ENDER_EYE") >= Milestone.EYES_NEEDED || f.endPortalReady),
    EYES_OF_ENDER(Stage.EYES_OF_ENDER, Kind.CRAFT, Cost.NONE, "엔더의 눈",
            f -> f.count("ENDER_EYE") >= Milestone.EYES_NEEDED || f.endPortalReady),

    STRONGHOLD(Stage.STRONGHOLD, Kind.WORLD, Cost.NONE, "요새 위치", f -> f.strongholdFound || f.endPortalReady),
    END_PORTAL(Stage.STRONGHOLD, Kind.WORLD, Cost.NONE, "엔드 포탈 활성화", f -> f.endPortalReady),

    END_GEAR(Stage.END_PREPARATION, Kind.GATHER, Cost.NONE, "활과 화살",
            f -> f.has("BOW") && f.count("ARROW") >= Milestone.ARROWS_NEEDED),

    ENDER_DRAGON(Stage.DRAGON_FIGHT, Kind.WORLD, Cost.NONE, "엔더 드래곤", f -> f.dragonDefeated);

    public enum Kind {
        // 제작으로 얻는다
        CRAFT,
        // 캐거나 사냥해서 모은다
        GATHER,
        // 월드에서 이뤄야 하는 일 (건설, 탐색, 전투)
        WORLD
    }

    // 만드는 데 드는 재료 중 따로 구하러 다녀야 하는 것
    private record Cost(boolean stone, int iron, int diamond) {
        static final Cost NONE = new Cost(false, 0, 0);
        static final Cost STONE = new Cost(true, 0, 0);

        static Cost iron(int ingots) {
            return new Cost(false, ingots, 0);
        }

        static Cost diamond(int diamonds) {
            return new Cost(false, 0, diamonds);
        }
    }

    public static final int PORTAL_OBSIDIAN = 10;
    // 엔드 포탈의 빈 틀을 채우고, 요새를 찾느라 던져서 깨지는 것까지 감안한 개수
    public static final int EYES_NEEDED = 12;
    public static final int ARROWS_NEEDED = 64;
    // 여기까지는 AI 가 스스로 해낼 수 있다. 그 뒤는 단계 정의만 있고, 실제로 수행하는 행동은 아직 없다.
    private static final Milestone LAST_AUTOMATED = FLINT_AND_STEEL;

    private final Stage stage;
    private final Kind kind;
    private final Cost cost;
    private final String label;
    private final Predicate<ProgressFacts> achieved;

    Milestone(Stage stage, Kind kind, Cost cost, String label, Predicate<ProgressFacts> achieved) {
        this.stage = stage;
        this.kind = kind;
        this.cost = cost;
        this.label = label;
        this.achieved = achieved;
    }

    public Stage stage() {
        return stage;
    }

    public Kind kind() {
        return kind;
    }

    // 사람에게 보여 줄 이름
    public String label() {
        return label;
    }

    public boolean isAchieved(ProgressFacts facts) {
        return achieved.test(facts);
    }

    public boolean isAutomated() {
        return ordinal() <= LAST_AUTOMATED.ordinal();
    }

    /**
     * 없어도 그 뒤의 일을 할 수 있는 항목인지. 이런 항목은 계속 실패하면 한동안 미뤄 두고 다음으로 넘어갈 수 있다.
     * 곡괭이처럼 뒤의 일에 꼭 필요한 것은 미룰 수 없다.
     */
    public boolean isOptional() {
        return this == SHELTER || this == SHIELD || this == BUCKET;
    }

    public boolean needsStone() {
        return cost.stone();
    }

    // 만드는 데 드는 철 주괴 개수. 철이 필요 없으면 0.
    public int ironCost() {
        return cost.iron();
    }

    public boolean needsIron() {
        return cost.iron() > 0;
    }

    public int diamondCost() {
        return cost.diamond();
    }
}
