package me.herry.minecraftAI.ai.primitive.control;

import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import me.herry.minecraftAI.ai.primitive.PrimitiveType;
import me.herry.minecraftAI.ai.util.BlockPoint;

import java.util.Objects;
import java.util.UUID;

/** Commands contain values only. The runtime resolves them to normal survival actions. */
public sealed interface PrimitiveCommand {
    PrimitiveType type();

    record MoveTo(BlockPoint target, double radius) implements PrimitiveCommand {
        public MoveTo {
            target = ContractValues.block(target);
            ContractValues.finiteRange(radius, 0.1, 16, "radius");
        }
        @Override public PrimitiveType type() { return PrimitiveType.MOVE_TO; }
    }

    record LookAt(PrimitiveTarget.Point target) implements PrimitiveCommand {
        public LookAt { target = ContractValues.point(target); }
        @Override public PrimitiveType type() { return PrimitiveType.LOOK_AT; }
    }

    record Jump() implements PrimitiveCommand {
        @Override public PrimitiveType type() { return PrimitiveType.JUMP; }
    }

    record BreakBlock(BlockPoint target, ToolChoice tool, boolean sneaking) implements PrimitiveCommand {
        public BreakBlock {
            target = ContractValues.block(target);
            Objects.requireNonNull(tool, "tool");
        }
        public BreakBlock(BlockPoint target, ToolChoice tool) { this(target, tool, false); }
        @Override public PrimitiveType type() { return PrimitiveType.BREAK_BLOCK; }
    }

    record PlaceBlock(BlockPoint target, String material) implements PrimitiveCommand {
        public PlaceBlock {
            target = ContractValues.block(target);
            material = ContractValues.material(material);
        }
        @Override public PrimitiveType type() { return PrimitiveType.PLACE_BLOCK; }
    }

    record Eat() implements PrimitiveCommand {
        @Override public PrimitiveType type() { return PrimitiveType.USE_ITEM; }
    }

    record Attack(UUID entityId) implements PrimitiveCommand {
        public Attack { Objects.requireNonNull(entityId, "entityId"); }
        @Override public PrimitiveType type() { return PrimitiveType.ATTACK; }
    }

    record Pickup(double radius) implements PrimitiveCommand {
        public Pickup { ContractValues.finiteRange(radius, 0.1, 16, "radius"); }
        @Override public PrimitiveType type() { return PrimitiveType.PICKUP_ITEM; }
    }

    record Equip(ToolChoice tool) implements PrimitiveCommand {
        public Equip { Objects.requireNonNull(tool, "tool"); }
        @Override public PrimitiveType type() { return PrimitiveType.EQUIP_ITEM; }
    }

    record SelectSlot(int slot) implements PrimitiveCommand {
        public SelectSlot { ContractValues.range(slot, 0, 8, "slot"); }
        @Override public PrimitiveType type() { return PrimitiveType.SELECT_SLOT; }
    }

    record Craft(String material, int amount) implements PrimitiveCommand {
        public Craft {
            material = ContractValues.material(material);
            ContractValues.range(amount, 1, 64, "amount");
        }
        @Override public PrimitiveType type() { return PrimitiveType.CRAFT_ITEM; }
    }

    record Wait(int ticks) implements PrimitiveCommand {
        public Wait { ContractValues.range(ticks, 1, PrimitiveProgram.MAX_TICKS, "ticks"); }
        @Override public PrimitiveType type() { return PrimitiveType.WAIT; }
    }

    record Sneak(int ticks) implements PrimitiveCommand {
        public Sneak { ContractValues.range(ticks, 1, PrimitiveProgram.MAX_TICKS, "ticks"); }
        @Override public PrimitiveType type() { return PrimitiveType.SNEAK; }
    }

    record Swim(PrimitiveTarget.Point target, int timeoutTicks) implements PrimitiveCommand {
        public Swim {
            target = ContractValues.point(target);
            ContractValues.range(timeoutTicks, 1, PrimitiveProgram.MAX_TICKS, "timeoutTicks");
        }
        @Override public PrimitiveType type() { return PrimitiveType.SWIM; }
    }

    record DropJunk() implements PrimitiveCommand {
        @Override public PrimitiveType type() { return PrimitiveType.DROP_ITEM; }
    }
}
