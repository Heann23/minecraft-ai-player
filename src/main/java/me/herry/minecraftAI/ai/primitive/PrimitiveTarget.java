package me.herry.minecraftAI.ai.primitive;

import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.Objects;

/**
 * 기본 행동의 대상. 값만 담고 Bukkit 객체를 들고 있지 않아서, 기록으로 남기거나 서버 없이 비교할 수 있다.
 */
public sealed interface PrimitiveTarget {
    PrimitiveTarget NONE = new None();

    // 대상이 따로 없는 행동 (점프, 먹기 등)
    record None() implements PrimitiveTarget {
    }

    record Block(BlockPoint pos) implements PrimitiveTarget {
        public Block {
            Objects.requireNonNull(pos, "pos");
        }
    }

    record Point(double x, double y, double z) implements PrimitiveTarget {
    }

    /**
     * @param item Material 이름
     */
    record Item(String item, int amount) implements PrimitiveTarget {
        public Item {
            Objects.requireNonNull(item, "item");
        }
    }

    /**
     * @param type 엔티티 종류 (EntityType 이름)
     * @param id   엔티티의 UUID 문자열
     */
    record Entity(String type, String id) implements PrimitiveTarget {
        public Entity {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(id, "id");
        }
    }

    record Slot(int index) implements PrimitiveTarget {
    }

    record Ticks(int ticks) implements PrimitiveTarget {
    }

    // 지금 서 있는 곳에서 이 반경 안
    record Area(double radius) implements PrimitiveTarget {
    }
}
