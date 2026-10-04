package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.crafting.FuelMath;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.memory.MemoryType;
import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

/**
 * 가까운 화로에 재료와 연료를 넣고, 다 구워질 때까지 기다렸다가 결과물을 꺼낸다.
 * 굽는 시간은 실제 화로와 같다 (아이템 하나에 10초).
 */
public final class SmeltItemAction extends AbstractAction {
    private static final int REACH = 4;
    private static final int TICKS_PER_ITEM = 200;
    private static final int CHECK_INTERVAL = 20;
    private static final int STORAGE_SIZE = 36;

    private final Material input;
    private final int count;
    private BlockPoint furnacePos;
    private boolean loaded;

    public SmeltItemAction(Material input, int count) {
        super("SmeltItem", count * TICKS_PER_ITEM + 300);
        this.input = input;
        this.count = count;
    }

    @Override
    protected void onStart(AIPlayer ai) {
        Player player = ai.getPlayer();
        furnacePos = NearbyBlocks.find(player, Material.FURNACE, REACH);
        if (furnacePos == null) {
            fail("no furnace nearby");
            return;
        }
        ai.getMemory().rememberPermanent(MemoryType.FURNACE, ai.getWorldId(), furnacePos, ai.getTicks());

        FurnaceInventory furnace = furnaceInventory(player);
        if (furnace == null || !isEmpty(furnace.getSmelting()) || !isEmpty(furnace.getFuel()) || !isEmpty(furnace.getResult())) {
            fail("furnace is in use");
            return;
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
        // 연료가 모자라면 그 연료로 끝까지 구울 수 있는 만큼만 넣는다. 다 넣으면 구워지지 않은 채 남아서 끝없이 기다리게 된다.
        amount = Math.min(amount, FuelMath.itemsFor(fuel.getAmount(), itemsPerFuel(fuel)));

        inventory.removeItem(new ItemStack(input, amount));
        furnace.setSmelting(new ItemStack(input, amount));
        furnace.setFuel(fuel);
        loaded = true;
        player.swingMainHand();
        ai.getBody().inputMove(0.0F, 0.0F);
        ai.getBody().inputSprint(false);
        ai.debug("Smelting " + amount + " " + input + " with " + fuel.getAmount() + " " + fuel.getType());
    }

    @Override
    protected void onTick(AIPlayer ai) {
        Player player = ai.getPlayer();
        ai.getBody().lookAt(furnacePos.x() + 0.5, furnacePos.y() + 0.5, furnacePos.z() + 0.5);
        if (getElapsed() % CHECK_INTERVAL != 0) return;

        FurnaceInventory furnace = furnaceInventory(player);
        if (furnace == null) {
            fail("furnace disappeared");
            return;
        }
        // 재료 칸이 비었으면 다 구워진 것이다.
        if (isEmpty(furnace.getSmelting())) succeed();
    }

    @Override
    protected void onEnd(AIPlayer ai) {
        // 끝났든 도중에 중단됐든, 화로에 넣어 둔 것을 두고 가지 않는다.
        if (!loaded) return;
        FurnaceInventory furnace = furnaceInventory(ai.getPlayer());
        if (furnace != null) collect(ai.getPlayer(), furnace);
    }

    // 화로에 있는 결과물, 남은 재료, 남은 연료를 모두 꺼내 인벤토리에 넣는다.
    private static void collect(Player player, FurnaceInventory furnace) {
        take(player, furnace.getResult());
        furnace.setResult(null);
        take(player, furnace.getSmelting());
        furnace.setSmelting(null);
        take(player, furnace.getFuel());
        furnace.setFuel(null);
    }

    private static void take(Player player, @Nullable ItemStack item) {
        if (isEmpty(item)) return;
        for (ItemStack leftover : player.getInventory().addItem(item.clone()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private @Nullable FurnaceInventory furnaceInventory(Player player) {
        if (!NearbyBlocks.is(player.getWorld(), furnacePos, Material.FURNACE)) return null;
        // 스냅샷이 아닌 실제 화로의 내용물을 다뤄야 넣고 꺼낸 것이 반영된다.
        return Positions.block(player.getWorld(), furnacePos).getState(false) instanceof Furnace furnace ? furnace.getInventory() : null;
    }

    // 필요한 만큼의 연료를 인벤토리에서 꺼낸다. 석탄과 숯을 먼저 쓰고, 없으면 판자나 원목을 쓴다.
    private static @Nullable ItemStack takeFuel(PlayerInventory inventory, int items) {
        ItemStack best = null;
        int bestSlot = -1;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || !InventorySystem.isFuel(item.getType())) continue;
            if (best == null || isCoal(item) && !isCoal(best) || isCoal(item) == isCoal(best) && item.getAmount() > best.getAmount()) {
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

    private static double itemsPerFuel(ItemStack fuel) {
        boolean wood = Tag.PLANKS.isTagged(fuel.getType()) || Tag.LOGS.isTagged(fuel.getType());
        return wood ? FuelMath.ITEMS_PER_WOOD : FuelMath.ITEMS_PER_COAL;
    }

    private static boolean isCoal(ItemStack item) {
        return item.getType() == Material.COAL || item.getType() == Material.CHARCOAL;
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.isEmpty();
    }
}
