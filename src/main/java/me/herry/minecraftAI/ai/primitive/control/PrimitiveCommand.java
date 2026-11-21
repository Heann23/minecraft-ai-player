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

    /** Use the already-held flint and steel on the top face of the selected portal base block. */
    record IgnitePortal(UUID world, BlockPoint floor) implements PrimitiveCommand {
        public IgnitePortal {
            Objects.requireNonNull(world, "world");
            floor = ContractValues.block(floor);
        }

        @Override public PrimitiveType type() { return PrimitiveType.USE_ITEM; }
    }

    /** Scoop a visible, reachable water or lava source block using the already-held empty bucket. */
    record FillBucket(UUID world, BlockPoint source, String fluid) implements PrimitiveCommand {
        public FillBucket {
            Objects.requireNonNull(world, "world");
            source = ContractValues.block(source);
            fluid = ContractValues.material(fluid);
            if (!fluid.equals("WATER") && !fluid.equals("LAVA"))
                throw new IllegalArgumentException("bucket fill requires WATER or LAVA source");
        }

        @Override public PrimitiveType type() { return PrimitiveType.USE_ITEM; }
    }

    /** Pour the already-held water or lava bucket onto the top face of the selected solid support block. */
    record PourBucket(UUID world, BlockPoint support, String bucket) implements PrimitiveCommand {
        public PourBucket {
            Objects.requireNonNull(world, "world");
            support = ContractValues.block(support);
            bucket = ContractValues.material(bucket);
            if (!bucket.equals("WATER_BUCKET") && !bucket.equals("LAVA_BUCKET"))
                throw new IllegalArgumentException("bucket pour requires WATER_BUCKET or LAVA_BUCKET");
        }

        @Override public PrimitiveType type() { return PrimitiveType.USE_ITEM; }
    }

    /** Walk into the selected active Nether portal cell and wait for normal dimension travel. */
    record EnterNetherPortal(UUID world, BlockPoint portal) implements PrimitiveCommand {
        public EnterNetherPortal {
            Objects.requireNonNull(world, "world");
            portal = ContractValues.block(portal);
        }

        @Override public PrimitiveType type() { return PrimitiveType.INTERACT_BLOCK; }
    }

    record EnterEndPortal(UUID world, BlockPoint portal) implements PrimitiveCommand {
        public EnterEndPortal {
            Objects.requireNonNull(world, "world");
            portal = ContractValues.block(portal);
        }

        @Override public PrimitiveType type() { return PrimitiveType.INTERACT_BLOCK; }
    }

    record ThrowEnderEye(UUID world) implements PrimitiveCommand {
        public ThrowEnderEye { Objects.requireNonNull(world, "world"); }
        @Override public PrimitiveType type() { return PrimitiveType.USE_ITEM; }
    }

    record SocketEnderEye(UUID world, BlockPoint frame) implements PrimitiveCommand {
        public SocketEnderEye {
            Objects.requireNonNull(world, "world");
            frame = ContractValues.block(frame);
        }
        @Override public PrimitiveType type() { return PrimitiveType.USE_ITEM; }
    }

    record Attack(UUID entityId) implements PrimitiveCommand {
        public Attack { Objects.requireNonNull(entityId, "entityId"); }
        @Override public PrimitiveType type() { return PrimitiveType.ATTACK; }
    }

    record ShootBow(UUID world, UUID entityId) implements PrimitiveCommand {
        public ShootBow {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(entityId, "entityId");
        }
        @Override public PrimitiveType type() { return PrimitiveType.USE_ITEM; }
    }

    record Pickup(double radius) implements PrimitiveCommand {
        public Pickup { ContractValues.finiteRange(radius, 0.1, 16, "radius"); }
        @Override public PrimitiveType type() { return PrimitiveType.PICKUP_ITEM; }
    }

    record PickupExact(UUID world, UUID entityId, String material) implements PrimitiveCommand {
        public PickupExact {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(entityId, "entityId");
            material = ContractValues.material(material);
        }
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

    enum FurnaceSlot { INGREDIENT, FUEL, RESULT }

    /** Insert one item from the selected storage slot, or take the matching result into storage. */
    record FurnaceTransfer(UUID world, BlockPoint target, FurnaceSlot slot, int inventorySlot,
                            String material) implements PrimitiveCommand {
        public FurnaceTransfer {
            Objects.requireNonNull(world, "world");
            target = ContractValues.block(target);
            Objects.requireNonNull(slot, "slot");
            ContractValues.range(inventorySlot, slot == FurnaceSlot.RESULT ? -1 : 0,
                    slot == FurnaceSlot.RESULT ? -1 : 35, "inventorySlot");
            material = ContractValues.material(material);
        }

        @Override public PrimitiveType type() { return PrimitiveType.INTERACT_BLOCK; }
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
