package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.FurnaceSlot;
import me.herry.minecraftAI.ai.primitive.control.PrimitiveCommand.FurnaceTransfer;
import me.herry.minecraftAI.ai.primitive.runtime.PrimitiveAdapter;
import me.herry.minecraftAI.ai.util.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/** A single selected transfer through a normal furnace menu. The furnace cooks on its own ticks. */
public final class FurnaceSlotAction extends AbstractAction {
    private final FurnaceTransfer transfer;
    private FurnaceMenu menu;

    public FurnaceSlotAction(FurnaceTransfer transfer) {
        super("FurnaceSlot", 40);
        this.transfer = transfer;
    }

    public static String unavailableReason(AIPlayer ai, FurnaceTransfer transfer) {
        var inventory = Furnaces.inventory(ai.getPlayer().getWorld(), transfer.target());
        if (inventory == null) return "furnace is absent";
        Material material = Material.getMaterial(transfer.material());
        if (material == null) return "unknown furnace item";
        var target = switch (transfer.slot()) {
            case INGREDIENT -> inventory.getSmelting();
            case FUEL -> inventory.getFuel();
            case RESULT -> inventory.getResult();
        };
        if (transfer.slot() == FurnaceSlot.RESULT)
            return !Furnaces.isEmpty(target) && target.getType() == material ? "" : "furnace result changed";
        var source = ai.getPlayer().getInventory().getItem(transfer.inventorySlot());
        if (Furnaces.isEmpty(source) || source.getType() != material) return "selected storage item changed";
        if (!Furnaces.isEmpty(target) && (!target.isSimilar(source) || target.getAmount() >= target.getMaxStackSize()))
            return "selected furnace slot cannot stack this item";
        return switch (transfer.slot()) {
            case INGREDIENT -> ai.getCrafting().smeltingResult(material) != null ? "" : "no supported furnace recipe for item";
            case FUEL -> CraftItemStack.asNMSCopy(source).has(DataComponents.COOKING_FUEL) ? "" : "item is not furnace fuel";
            case RESULT -> ""; // The output is checked above and has no source slot.
        };
    }

    @Override protected void onStart(AIPlayer ai) {
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
    }

    @Override protected void onTick(AIPlayer ai) {
        var check = PrimitiveAdapter.check(ai, transfer);
        if (!check.allowed()) {
            fail(check.reason());
            return;
        }
        var player = ai.getPlayer();
        var handle = ((CraftPlayer) player).getHandle();
        if (handle.containerMenu != handle.inventoryMenu || !handle.inventoryMenu.getCarried().isEmpty()) {
            fail("existing menu or cursor is occupied");
            return;
        }
        var center = Positions.center(player.getWorld(), transfer.target());
        ai.getBody().lookAt(center.getX(), center.getY(), center.getZ());
        var hit = player.rayTraceBlocks(handle.blockInteractionRange(), FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null
                || !transfer.target().equals(Positions.of(hit.getHitBlock()))) return;
        var point = new BlockPos(transfer.target().x(), transfer.target().y(), transfer.target().z());
        if (!handle.isWithinBlockInteractionRange(point, 0)) {
            fail("furnace outside native interaction range");
            return;
        }
        var impact = hit.getHitPosition();
        handle.gameMode.useItemOn(handle, handle.level(), handle.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(new Vec3(impact.getX(), impact.getY(), impact.getZ()),
                        Direction.valueOf(hit.getHitBlockFace().name()), point, false));
        if (!(handle.containerMenu instanceof FurnaceMenu opened)) {
            fail("native furnace interaction denied");
            return;
        }
        menu = opened;
        if (!menu.getCarried().isEmpty() || !menu.stillValid(handle)) {
            fail("furnace menu unavailable");
            return;
        }
        int destination = switch (transfer.slot()) {
            case INGREDIENT -> FurnaceMenu.INGREDIENT_SLOT;
            case FUEL -> FurnaceMenu.FUEL_SLOT;
            case RESULT -> FurnaceMenu.RESULT_SLOT;
        };
        var furnaceSlot = menu.getSlot(destination);
        var before = furnaceSlot.getItem();
        int beforeCount = before.getCount();
        if (destination == FurnaceMenu.RESULT_SLOT) {
            if (matchesMaterial(before)) menu.clicked(destination, 0, ContainerInput.QUICK_MOVE, handle);
            if (furnaceSlot.getItem().getCount() < beforeCount) succeed();
            else fail("matching furnace result could not be transferred");
            return;
        }
        var source = menu.slots.stream()
                .filter(slot -> slot.container == handle.getInventory() && slot.getContainerSlot() == transfer.inventorySlot())
                .findFirst().orElse(null);
        if (source == null || !matchesMaterial(source.getItem()) || !furnaceSlot.mayPlace(source.getItem())
                || beforeCount >= furnaceSlot.getMaxStackSize(source.getItem())
                || (!before.isEmpty() && !ItemStack.isSameItemSameComponents(before, source.getItem()))) {
            fail("source or furnace slot changed before transfer");
            return;
        }
        menu.clicked(source.index, 0, ContainerInput.PICKUP, handle);
        if (matchesMaterial(menu.getCarried())) menu.clicked(destination, 1, ContainerInput.PICKUP, handle);
        if (!menu.getCarried().isEmpty()) menu.clicked(source.index, 0, ContainerInput.PICKUP, handle);
        if (furnaceSlot.getItem().getCount() == beforeCount + 1 && menu.getCarried().isEmpty()) succeed();
        else fail("native furnace insertion did not move exactly one item");
    }

    private boolean matchesMaterial(ItemStack stack) {
        return !stack.isEmpty() && transfer.material().equals(CraftItemStack.asBukkitCopy(stack).getType().name());
    }

    @Override protected void onEnd(AIPlayer ai) {
        var handle = ((CraftPlayer) ai.getPlayer()).getHandle();
        if (menu != null && handle.containerMenu == menu) handle.closeContainer();
    }
}
