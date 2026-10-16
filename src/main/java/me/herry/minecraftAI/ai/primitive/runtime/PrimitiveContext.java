package me.herry.minecraftAI.ai.primitive.runtime;

import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveSnapshot;
import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded, value-only target observations. This is not a complete map of the surrounding world. */
public record PrimitiveContext(PrimitiveSnapshot state, List<BlockView> blocks, List<EntityView> entities,
                               boolean complete) {
    public PrimitiveContext {
        Objects.requireNonNull(state, "state");
        blocks = List.copyOf(blocks);
        entities = List.copyOf(entities);
        if (blocks.size() > PrimitiveAdapter.MAX_CANDIDATES || entities.size() > PrimitiveAdapter.MAX_CANDIDATES) {
            throw new IllegalArgumentException("context target limits exceeded");
        }
    }

    /** UNKNOWN material means no current visible observation; source may identify a remembered landmark. */
    public record BlockView(BlockPoint point, String material, boolean loaded, boolean visible, String source) {
        public BlockView {
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(source, "source");
        }
    }

    /** itemMaterial is AIR and amount zero for living entities; health is zero for item drops. */
    public record EntityView(UUID id, String type, PrimitiveTarget.Point position, double health,
                             String itemMaterial, int amount, boolean visible) {
        public EntityView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(itemMaterial, "itemMaterial");
        }
    }
}
