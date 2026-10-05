package me.herry.minecraftAI.ai.action;

import me.herry.minecraftAI.ai.util.BlockPoint;
import me.herry.minecraftAI.ai.util.Positions;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 화로의 내용물을 읽고 꺼내는 공통 코드.
 */
public final class Furnaces {
    private Furnaces() {
    }

    // 그 자리에 있는 화로의 내용물. 화로가 없거나 청크가 내려가 있으면 null.
    public static @Nullable FurnaceInventory inventory(World world, BlockPoint pos) {
        if (!NearbyBlocks.is(world, pos, Material.FURNACE)) return null;
        // 스냅샷이 아닌 실제 화로의 내용물을 다뤄야 넣고 꺼낸 것이 반영된다.
        return Positions.block(world, pos).getState(false) instanceof Furnace furnace ? furnace.getInventory() : null;
    }

    public static boolean isEmpty(FurnaceInventory furnace) {
        return isEmpty(furnace.getSmelting()) && isEmpty(furnace.getFuel()) && isEmpty(furnace.getResult());
    }

    // 넣은 재료가 다 구워졌는지 (재료 칸이 비었는지)
    public static boolean isSmelted(FurnaceInventory furnace) {
        return isEmpty(furnace.getSmelting());
    }

    /**
     * 화로에 있는 결과물, 남은 재료, 남은 연료를 모두 꺼내 인벤토리에 넣는다. 가방에 자리가 없으면 발밑에 떨어뜨린다.
     *
     * @return 꺼낸 결과물. 결과물이 없었으면 null.
     */
    public static @Nullable ItemStack collect(Player player, FurnaceInventory furnace) {
        ItemStack result = isEmpty(furnace.getResult()) ? null : furnace.getResult().clone();
        take(player, furnace.getResult());
        furnace.setResult(null);
        take(player, furnace.getSmelting());
        furnace.setSmelting(null);
        take(player, furnace.getFuel());
        furnace.setFuel(null);
        return result;
    }

    private static void take(Player player, @Nullable ItemStack item) {
        if (isEmpty(item)) return;
        for (ItemStack leftover : player.getInventory().addItem(item.clone()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.isEmpty();
    }
}
