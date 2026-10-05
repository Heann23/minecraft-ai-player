package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.crafting.FuelMath;
import me.herry.minecraftAI.ai.crafting.FurnaceJob;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

/**
 * 가까운 화로에 재료와 연료를 넣는다. 다 구워질 때까지 화로 앞에서 기다리지 않고, 넣어 둔 화로를 기억해 두고 끝난다.
 * 그동안 다른 일을 하다가 다 구워지면 돌아와서 꺼낸다 (CollectFurnaceAction). 싸우러 가느라 중단돼도 넣은 것은 그대로 구워진다.
 * 굽는 시간은 실제 화로와 같다 (아이템 하나에 10초).
 */
public final class SmeltItemAction extends AbstractAction {
    private static final int REACH = 4;
    private static final int TIMEOUT = 40;
    private static final int STORAGE_SIZE = 36;

    private final Material input;
    private final int count;

    public SmeltItemAction(Material input, int count) {
        super("SmeltItem", TIMEOUT);
        this.input = input;
        this.count = count;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        Player player = ai.getPlayer();
        BlockPoint furnacePos = NearbyBlocks.find(player, Material.FURNACE, REACH);
        if (furnacePos == null) {
            fail("no furnace nearby");
            return;
        }
        ai.getMemory().rememberPermanent(MemoryType.FURNACE, ai.getWorldId(), furnacePos, ai.getTicks());

        FurnaceInventory furnace = Furnaces.inventory(player.getWorld(), furnacePos);
        if (furnace == null) {
            fail("furnace disappeared");
            return;
        }
        if (!Furnaces.isEmpty(furnace)) {
            // 남이 쓰고 있는 화로는 건드리지 않는다. 자기 화로에 남아 있는 것은 전에 넣어 두고 가져가지 못한 것이므로 먼저 꺼낸다.
            if (!isOwn(ai, furnacePos)) {
                fail("furnace is in use");
                return;
            }
            Furnaces.collect(player, furnace);
            ai.debug("Took out what was left in the furnace at " + furnacePos);
        }

        PlayerInventory inventory = player.getInventory();
        int amount = Math.min(count, ai.getInventory().count(input));
        if (amount <= 0) {
            fail("nothing to smelt");
            return;
        }
        ItemStack fuel = takeFuel(inventory, amount);
        if (fuel == null) {
            fail("no fuel");
            return;
        }
        // 연료가 모자라면 그 연료로 끝까지 구울 수 있는 만큼만 넣는다. 다 넣으면 구워지지 않은 재료가 화로에 남는다.
        amount = Math.min(amount, FuelMath.itemsFor(fuel.getAmount(), itemsPerFuel(fuel)));

        inventory.removeItem(new ItemStack(input, amount));
        furnace.setSmelting(new ItemStack(input, amount));
        furnace.setFuel(fuel);
        FurnaceJob left = ai.getFurnaceJob();
        if (left != null && !left.isAt(ai.getWorldId(), furnacePos)) {
            ai.debug("Leaving behind what was put in the furnace at " + left.pos());
        }
        ai.setFurnaceJob(FurnaceJob.start(ai.getWorldId(), furnacePos, InventorySystem.isRawFood(input), amount, ai.getTicks()));
        player.swingMainHand();
        ai.getBody().lookAt(furnacePos.x() + 0.5, furnacePos.y() + 0.5, furnacePos.z() + 0.5);
        ai.debug("Smelting " + amount + " " + input + " with " + fuel.getAmount() + " " + fuel.getType());
        succeed();
    }

    @Override
    protected void onTick(AIPlayer ai) {
    }

    // 이 AI 가 직접 놓았거나 자기 집에 들여놓은 화로인지
    private static boolean isOwn(AIPlayer ai, BlockPoint furnacePos) {
        if (ai.getMemory().contains(MemoryType.OWN_FURNACE, ai.getWorldId(), furnacePos, ai.getTicks())) return true;
        Base home = ai.getWorldModel().homeIn(ai.getWorldId());
        return home != null && furnacePos.equals(home.furnace());
    }

    /**
     * 필요한 만큼의 연료를 인벤토리에서 꺼낸다. 석탄과 숯을 먼저 쓰고, 없으면 판자를, 판자도 없으면 원목을 쓴다.
     * 원목은 판자로 바꾸면 네 배를 구울 수 있어서, 계획을 세울 때 미리 판자로 만들어 둔다 (FurnacePlans.splitLogsForFuel).
     */
    private static @Nullable ItemStack takeFuel(PlayerInventory inventory, int items) {
        ItemStack best = null;
        int bestSlot = -1;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || !InventorySystem.isFuel(item.getType())) continue;
            int rank = fuelRank(item);
            if (best == null || rank > fuelRank(best) || rank == fuelRank(best) && item.getAmount() > best.getAmount()) {
                best = item;
                bestSlot = slot;
            }
        }
        if (best == null) return null;

        int amount = Math.min(FuelMath.fuelFor(items, itemsPerFuel(best)), best.getAmount());
        ItemStack fuel = new ItemStack(best.getType(), amount);
        if (amount >= best.getAmount()) inventory.setItem(bestSlot, null);
        else best.setAmount(best.getAmount() - amount);
        return fuel;
    }

    private static int fuelRank(ItemStack item) {
        if (item.getType() == Material.COAL || item.getType() == Material.CHARCOAL) return 2;
        return Tag.PLANKS.isTagged(item.getType()) ? 1 : 0;
    }

    private static double itemsPerFuel(ItemStack fuel) {
        boolean wood = Tag.PLANKS.isTagged(fuel.getType()) || Tag.LOGS.isTagged(fuel.getType());
        return wood ? FuelMath.ITEMS_PER_WOOD : FuelMath.ITEMS_PER_COAL;
    }
}
