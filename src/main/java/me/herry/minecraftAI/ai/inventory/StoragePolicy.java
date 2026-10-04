package me.herry.minecraftAI.ai.inventory;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 가방의 아이템 중 무엇을 몸에 지니고 무엇을 집 상자에 넣어 둘지 정한다.
 * 종류별로 "지니고 다닐 양"이 있고, 그보다 많은 것만 상자에 넣는다. 지금 만들려는 장비에 필요한 재료는 넣지 않는다.
 * 쓰지 않는 잡동사니를 버리는 일은 JunkPolicy 가 따로 맡는다. Bukkit 에 의존하지 않는다.
 */
public final class StoragePolicy {
    /**
     * 보관 판단에 쓰는 아이템의 종류.
     */
    public enum Kind {
        TOOL(Category.TOOL), ARMOR(Category.ARMOR),
        // 양동이, 부싯돌과 부시, 활, 화살, 방패처럼 늘 지니고 다니는 것
        KIT(Category.COMBAT),
        FOOD(Category.FOOD),
        LOG(Category.BLOCK), PLANK(Category.BLOCK), STICK(Category.BLOCK),
        // 조약돌처럼 도구 재료가 되는 돌과, 흙처럼 메우는 데만 쓰는 블록
        STONE(Category.BLOCK), FILLER(Category.BLOCK), WOOL(Category.BLOCK),
        COAL(Category.ORE), TORCH(Category.BLOCK),
        IRON(Category.ORE), DIAMOND(Category.ORE),
        // 그 밖의 광물과 귀한 재료 (금, 레드스톤, 청금석, 에메랄드, 흑요석 ...)
        VALUABLE(Category.ORE),
        // 네더와 엔드에서 얻는, 클리어에 꼭 필요한 재료 (블레이즈 막대, 엔더 진주, 엔더의 눈)
        NETHER(Category.NETHER), END(Category.END),
        TABLE(Category.BLOCK), FURNACE(Category.BLOCK),
        // 보관할 가치가 없는 것. 상자에 넣지 않는다.
        OTHER(Category.MISC);

        private final Category category;

        Kind(Category category) {
            this.category = category;
        }

        public Category category() {
            return category;
        }
    }

    /**
     * 상자 안의 물건을 사람이 보기 좋게 묶는 분류.
     */
    public enum Category { ORE, BLOCK, FOOD, TOOL, ARMOR, COMBAT, NETHER, END, MISC }

    /**
     * 지금 만들려는 것에 필요한 재료. 필요한 재료는 지니고 다녀야 하므로 상자에 넣지 않는다.
     */
    public record Context(boolean needsWood, boolean needsStone, boolean needsIron, boolean needsDiamond) {
        public static final Context NOTHING_NEEDED = new Context(false, false, false, false);
    }

    /**
     * 가방 한 칸.
     */
    public record Stack(int slot, Kind kind, int amount) {
    }

    public static final int KEEP_ALL = Integer.MAX_VALUE;
    private static final Map<Kind, Integer> CARRY = new EnumMap<>(Kind.class);

    static {
        CARRY.put(Kind.TOOL, KEEP_ALL);
        CARRY.put(Kind.ARMOR, KEEP_ALL);
        CARRY.put(Kind.KIT, KEEP_ALL);
        CARRY.put(Kind.OTHER, KEEP_ALL);
        CARRY.put(Kind.FOOD, 16);
        CARRY.put(Kind.LOG, 8);
        CARRY.put(Kind.PLANK, 16);
        CARRY.put(Kind.STICK, 16);
        // 다리를 놓거나 발판을 쌓고 물을 막는 데 쓸 만큼은 지닌다.
        CARRY.put(Kind.STONE, 32);
        CARRY.put(Kind.FILLER, 32);
        // 침대 하나를 만들 만큼
        CARRY.put(Kind.WOOL, 3);
        CARRY.put(Kind.COAL, 16);
        CARRY.put(Kind.TORCH, 32);
        CARRY.put(Kind.IRON, 0);
        CARRY.put(Kind.DIAMOND, 0);
        CARRY.put(Kind.VALUABLE, 0);
        CARRY.put(Kind.NETHER, 0);
        CARRY.put(Kind.END, 0);
        CARRY.put(Kind.TABLE, 1);
        CARRY.put(Kind.FURNACE, 1);
    }

    private StoragePolicy() {
    }

    // 그 종류를 몸에 지니고 다닐 최대 개수
    public static int carryLimit(Kind kind, Context context) {
        boolean needed = switch (kind) {
            case LOG, PLANK, STICK -> context.needsWood();
            case STONE, FILLER -> context.needsStone();
            case IRON -> context.needsIron();
            case DIAMOND -> context.needsDiamond();
            default -> false;
        };
        return needed ? KEEP_ALL : CARRY.get(kind);
    }

    /**
     * 상자에 넣을 것을 고른다. 앞쪽 칸(핫바)부터 지닐 양을 채우므로, 손에 익은 칸의 것이 남고 뒤쪽 칸의 것이 들어간다.
     *
     * @return 칸 번호 -> 그 칸에서 꺼내 상자에 넣을 개수
     */
    public static Map<Integer, Integer> deposits(List<Stack> stacks, Context context) {
        Map<Kind, Integer> kept = new EnumMap<>(Kind.class);
        Map<Integer, Integer> deposits = new LinkedHashMap<>();
        for (Stack stack : stacks) {
            int limit = carryLimit(stack.kind(), context);
            if (limit == KEEP_ALL) continue;
            int already = kept.getOrDefault(stack.kind(), 0);
            int keep = Math.max(0, Math.min(stack.amount(), limit - already));
            kept.put(stack.kind(), already + keep);
            if (keep < stack.amount()) deposits.put(stack.slot(), stack.amount() - keep);
        }
        return deposits;
    }
}
